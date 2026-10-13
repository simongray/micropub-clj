(ns dk.simongray.micropub.server
  "The Micropub endpoint and the media endpoint as Ring handlers. They
  check the access token, read the request and pass it to your functions,
  which store the posts and the files.

  The code follows the W3C Recommendation of 23 May 2017 at
  https://www.w3.org/TR/micropub/ and cites its sections. The handlers
  check access tokens with indieauth-clj, in the token store of a server
  in the same process or at the introspection endpoint of one elsewhere."
  (:require [clojure.string :as str]
            [dk.simongray.indieauth :as indieauth]
            [dk.simongray.indieauth.resource :as resource]
            [dk.simongray.micropub :as micropub]
            [dk.simongray.micropub.impl.request :as request]
            [dk.simongray.micropub.impl.ring :as ring]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(def default-options
  "The defaults of the handlers' options:

  - :scopes, for each kind of request (:create, :update, :delete,
    :undelete, :media and :query), the scopes of which a token needs one.
    Micropub 5.4 names create, update and delete. An undelete also takes
    the undelete scope that micropub.rocks asks for, and an upload takes
    create, since clients seldom ask for media.
  - :insufficient-scope-status, 401 as in Micropub 3.8, where the living
    standard and RFC 6750 have 403
  - :max-bytes, 1 MB, the largest form or JSON body that the handlers read
    themselves

  A token with the draft scope but none of the :create scopes may create
  and update drafts, as
  https://github.com/indieweb/micropub-extensions/issues/24 proposes."
  {:scopes                    {:create   ["create"]
                               :update   ["update"]
                               :delete   ["delete"]
                               :undelete ["undelete" "delete"]
                               :media    ["media" "create"]
                               :query    ["create" "update" "delete"
                                          "undelete" "media" "draft"]}
   :insufficient-scope-status 401
   :max-bytes                 (* 1024 1024)})

;;; Answers

;; Micropub 3.8
(def statuses
  "The HTTP status of each error code of Micropub 3.8."
  {"invalid_request"    400
   "unauthorized"       401
   "forbidden"          403
   "insufficient_scope" 401})

(defn problem-response
  "The Ring response to the `problem`, a map of a Micropub error :code and
  its :reason (Micropub 3.8).

  The status is the code's unless the problem has a :status. A :scope
  names the scope that the request needs, and a :challenge becomes the
  WWW-Authenticate header of RFC 6750."
  [{:keys [status code reason scope challenge] :as problem}]
  (cond-> (ring/json-response (or status (statuses code 400))
                              (cond-> {:error code}
                                reason (assoc :error_description reason)
                                scope  (assoc :scope scope)))
    challenge (assoc-in [:headers "WWW-Authenticate"] challenge)))

(defn- answer
  "The Ring response to the `result` of an update, delete or undelete: 204
  for nil, 201 with the Location of a post that moved (Micropub 3.4.4 and
  3.5.1), or the answer to an :error."
  [{:keys [url error] :as result}]
  (cond
    error (problem-response error)
    url   (ring/response 201 {"Location" url} "")
    :else (ring/response 204 {} "")))

;; Micropub 3.3.6
(defn- created
  "The Ring response to the `result` of :create!: its :status, 201 by
  default, with the Location of its :url."
  [{:keys [url status error] :as result}]
  (cond
    error
    (problem-response error)

    (string? url)
    (ring/response (or status 201) {"Location" url} "")

    :else
    (throw (ex-info "A :create! returns the :url of the new post"
                    {:result result}))))

(defn- invalid
  "An invalid_request problem with the `reason`."
  [reason]
  {:code "invalid_request" :reason reason})

(defn- no-post
  "The problem of a request for a post at `url` that doesn't exist."
  [url]
  (invalid (str "There's no post at " url)))

;;; Access

;; Micropub 5.1; RFC 6750, section 2
(defn- token-of
  "The access token in the header of the Ring `request` or its form
  `fields`, as a map of the :token, or of the :error when there's none or
  one in each."
  [request fields]
  (let [header (resource/header-token request)
        field  (not-empty (request/field-value fields "access_token"))]
    (cond
      (and header field)
      {:error {:code   "invalid_request"
               :reason "The access token is in both the header and the body"}}

      (or header field)
      {:token (or header field)}

      :else
      {:error {:code      "unauthorized"
               :reason    "The request has no access token"
               :challenge "Bearer"}})))

(defn- token-info!
  "The information on the access `token` from the token store or the
  introspection endpoint of `opts`, as indieauth-clj returns it."
  [token {:keys [tokens] :as opts}]
  (if tokens
    (resource/token-info! token opts)
    (resource/introspect! token opts)))

;; IndieAuth 6; Micropub 3.8
(defn- identity-problem
  "Why the access token of `info` can't be used at all, given the :me-pred
  of `opts`, or nil."
  [info {:keys [me-pred] :as opts}]
  (cond
    (:error info)
    {:status 503
     :code   "temporarily_unavailable"
     :reason "The access token couldn't be checked"}

    (nil? info)
    {:code      "unauthorized"
     :reason    "The access token is unknown, revoked or expired"
     :challenge "Bearer error=\"invalid_token\""}

    (not (me-pred (:me info)))
    {:code "forbidden" :reason "The access token isn't for this site"}))

(defn- authenticated!
  "The information on the access token of the Ring `request` and its form
  `fields`, checked with `opts`, or a map of the :error."
  [request fields opts]
  (let [{:keys [token error]} (token-of request fields)]
    (if error
      {:error error}
      (async/then (token-info! token opts)
                  (fn [info]
                    (if-let [problem (identity-problem info opts)]
                      {:error problem}
                      info))))))

(defn- granted?
  "Whether the token of `info` has one of the `scopes`."
  [info scopes]
  (boolean (some (set (indieauth/scopes (:scope info))) scopes)))

;; Micropub 5.4 and 3.8; RFC 6750, section 3.1
(defn- scope-problem
  "The insufficient_scope problem of a request that needs one of the
  `scopes`, with the :insufficient-scope-status of `opts`."
  [scopes {:keys [insufficient-scope-status] :as opts}]
  (let [text (indieauth/scope-text scopes)]
    {:status    insufficient-scope-status
     :code      "insufficient_scope"
     :reason    (str "The access token needs one of the scopes " text)
     :scope     text
     :challenge (str "Bearer error=\"insufficient_scope\", scope=\"" text "\"")}))

;; https://github.com/indieweb/micropub-extensions/issues/24
(defn- drafts-only?
  "Whether the token of `info` may create and update only drafts, given
  the :scopes of `opts`."
  [info {:keys [scopes] :as opts}]
  (and (granted? info ["draft"])
       (not (granted? info (:create scopes)))))

(defn- draft?
  "Whether the post-status of `entry` is draft."
  [entry]
  (= ["draft"] (get-in entry [:properties :post-status])))

(defn- as-draft
  "The `entry` with the post-status draft."
  [entry]
  (assoc-in entry [:properties :post-status] ["draft"]))

;;; Reading

;; Micropub 3.1 and 3.3.2
(defn- body
  "The body of the Ring `request` of a POST as a map of its form :fields
  or its :json, read within the :max-bytes of `opts`, or a map of the
  :error."
  [{:keys [multipart-params] :as request} {:keys [max-bytes] :as opts}]
  (let [too-large (request/problem (str "The body is larger than " max-bytes " bytes"))]
    (case (ring/media-type request)
      "application/json"
      (if-some [json (ring/json-body request max-bytes)]
        {:json json}
        (request/problem "The body isn't JSON, or it's too large"))

      "multipart/form-data"
      (if (seq multipart-params)
        {:fields (ring/param-fields multipart-params)}
        ;; multipart bodies need the server's middleware
        (request/problem "The server can't read multipart bodies"))

      (nil "application/x-www-form-urlencoded")
      (if-some [fields (ring/form-fields request max-bytes)]
        {:fields fields}
        too-large)

      (request/problem "The body is neither a form nor JSON"))))

(defn action
  "The action in the Ring `request` of a POST, read within the :max-bytes
  of `opts`, or a map of the :error when the request isn't valid.

  The action is a map of its :action, one of :create, :update, :delete and
  :undelete, and of:

  - the :type, :properties and :commands of a create, and the :files of a
    multipart create by property, as Ring's multipart middleware reads
    them
  - the :url of the others
  - the :replace, :add and :delete of an update

  Every value is a vector, whatever the syntax, and the access token is
  left out."
  ([request]
   (action request {}))
  ([request opts]
   (let [{:keys [fields json error]} (body request (merge default-options opts))]
     (cond
       error  {:error error}
       fields (request/form-action fields)
       :else  (request/json-action json)))))

(defn query
  "The query in the Ring `request` of a GET: a map of its :q (e.g. :source)
  and its other parameters by name, such as the :url and :properties of a
  source query. The access token is left out."
  [request]
  (request/query (ring/query-fields request)))

;;; The Micropub endpoint

(defn- hide-field
  "The Ring `params` with the value of an access_token field hidden."
  [params]
  (reduce (fn [m k]
            (cond-> m
              (contains? m k) (update k #(if (sequential? %)
                                           (mapv secret/hide %)
                                           (secret/hide %)))))
          params
          ["access_token" :access_token]))

;; so that a request that you print or log doesn't show the token
(defn- hide-token
  "The Ring `request` with its access token hidden, in the Authorization
  header, in an access_token field of its params and in a body that's
  still a string."
  [request]
  (cond-> (reduce (fn [r k]
                    (cond-> r (map? (get r k)) (update k hide-field)))
                  request
                  [:params :form-params :multipart-params])
    (get-in request [:headers "authorization"])
    (update-in [:headers "authorization"] secret/hide)

    (string? (:body request))
    (update :body secret/hide)))

(defn- with-context
  "The `action` or query with the token `info` and the Ring `request`,
  whose token is hidden."
  [action info request]
  (assoc action :token-info info :ring-request (hide-token request)))

(defn- uploaded!
  "The create `action` with each of its :files uploaded with the :upload!
  of `opts` and replaced by its URL, after the property's other values."
  [{:keys [files token-info ring-request] :as action} {:keys [upload!] :as opts}]
  (let [pairs (for [[k fs] files, f fs] [k f])]
    (async/then (async/all (fn [[_ f]]
                             (upload! (assoc f :token-info token-info
                                               :ring-request ring-request)))
                           pairs)
                (fn [results]
                  (if-let [error (some :error results)]
                    {:error error}
                    (reduce (fn [a [[k _] url]]
                              (update-in a [:properties k] (fnil conj []) url))
                            (dissoc action :files)
                            (map vector pairs results)))))))

;; Micropub 3.3
(defn- create!
  "Answer the create `action` with the functions of `opts`, as a draft
  when `drafts?`."
  [action drafts? {:keys [create! upload!] :as opts}]
  (let [action (cond-> action drafts? as-draft)]
    (cond
      (and (seq (:files action)) (not upload!))
      (problem-response (invalid "The server doesn't accept files"))

      (seq (:files action))
      (async/then (uploaded! action opts)
                  (fn [action]
                    (if-let [error (:error action)]
                      (problem-response error)
                      (async/then (create! action) created))))

      :else
      (async/then (create! action) created))))

;; Micropub 3.4
(defn- update!
  "Answer the update `action` with the functions of `opts`, for a draft
  only when `drafts?`. The :update! gets the action with the :entry that
  apply-update makes from the one that :source returns."
  [{:keys [url] :as action} drafts? {:keys [source update!] :as opts}]
  (async/then (source action)
              (fn [entry]
                (cond
                  (:error entry)
                  (problem-response (:error entry))

                  (nil? entry)
                  (problem-response (no-post url))

                  (and drafts? (not (draft? entry)))
                  (problem-response (scope-problem (get-in opts [:scopes :update]) opts))

                  :else
                  (let [entry (cond-> (micropub/apply-update entry action)
                                drafts? as-draft)]
                    (async/then (update! (assoc action :entry entry)) answer))))))

(defn- unsupported
  "The answer to a request of `kind` that the server doesn't support."
  [kind]
  (problem-response (invalid (str "The server doesn't support " (name kind)))))

(defn- act!
  "Answer the `action` with the function of `opts` for its kind, if the
  token of `info` allows it."
  [{kind :action :as action} info {:keys [scopes] :as opts}]
  (let [drafts?  (drafts-only? info opts)
        f        (get opts (keyword (str (name kind) "!")))
        needs    (get scopes kind)
        allowed? (or (granted? info needs)
                     (and drafts? (#{:create :update} kind)))]
    (cond
      (not allowed?)
      (problem-response (scope-problem needs opts))

      (or (not f) (and (= :update kind) (not (:source opts))))
      (unsupported kind)

      (= :create kind)
      (create! action drafts? opts)

      (= :update kind)
      (update! action drafts? opts)

      :else
      (async/then (f action) answer))))

(defn- post-answer
  "The answer to a POST `request` to the Micropub endpoint, with `opts`."
  [request opts]
  (let [{:keys [fields json error]} (body request opts)]
    (if error
      (problem-response error)
      (async/then (authenticated! request fields opts)
                  (fn [info]
                    (let [action (if fields
                                   (request/form-action fields)
                                   (request/json-action json))]
                      (cond
                        (:error info)   (problem-response (:error info))
                        (:error action) (problem-response (:error action))
                        :else           (act! (with-context action info request)
                                              info
                                              opts))))))))

;; Micropub 3.7.1, and the list of queries of
;; https://github.com/indieweb/micropub-extensions/issues/7
(defn- config-answer
  "The answer to the config `query`, from the :config, :media-endpoint,
  :source and :queries of `opts`."
  [query {:keys [config media-endpoint source queries] :as opts}]
  (async/then (if (fn? config) (config query) config)
              (fn [config]
                (ring/json-response
                 200
                 (cond-> (or config {})
                   media-endpoint      (assoc :media-endpoint media-endpoint)
                   (not (:q config))   (assoc :q (vec (concat ["config" "syndicate-to"]
                                                          (when source ["source"])
                                                          (map name (keys queries))))))))))

;; Micropub 3.7.3
(defn- syndicate-to-answer
  "The answer to the syndicate-to `query`, from the :syndicate-to of the
  :config of `opts`."
  [query {:keys [config] :as opts}]
  (async/then (if (fn? config) (config query) config)
              (fn [config]
                (ring/json-response 200 {:syndicate-to (or (:syndicate-to config) [])}))))

;; Micropub 3.7.2
(defn- source-answer
  "The answer to the source `query` from the :source of `opts`: the whole
  entry, or only the properties that the query asks for."
  [{:keys [url properties] :as query} {:keys [source] :as opts}]
  (cond
    (not source)
    (unsupported :source)

    (str/blank? url)
    (problem-response (invalid "A source query needs a url"))

    :else
    (async/then (source query)
                (fn [entry]
                  (cond
                    (:error entry) (problem-response (:error entry))
                    (nil? entry)   (problem-response (no-post url))
                    properties     (ring/json-response
                                    200
                                    {:properties (select-keys (:properties entry) properties)})
                    :else          (ring/json-response 200 entry))))))

(defn- other-answer
  "The answer to any other `query`, from the :queries of `opts`."
  [{:keys [q] :as query} {:keys [queries] :as opts}]
  (if-let [f (get queries q)]
    (async/then (f query)
                (fn [result]
                  (if-let [error (:error result)]
                    (problem-response error)
                    (ring/json-response 200 result))))
    (problem-response (invalid (if q
                                 (str "The server doesn't answer q=" (name q))
                                 "A query needs a q")))))

(defn- get-answer
  "The answer to a GET `request` to the Micropub endpoint, a query, with
  `opts`."
  [request {:keys [scopes] :as opts}]
  ;; the header alone counts, since a token in the query of a URL ends up
  ;; in logs (RFC 6750, section 2.3)
  (async/then (authenticated! request [] opts)
              (fn [info]
                (let [asked (with-context (query request) info request)]
                  (cond
                    (:error info)
                    (problem-response (:error info))

                    (not (granted? info (:query scopes)))
                    (problem-response (scope-problem (:query scopes) opts))

                    :else
                    (case (:q asked)
                      :config       (config-answer asked opts)
                      :syndicate-to (syndicate-to-answer asked opts)
                      :source       (source-answer asked opts)
                      (other-answer asked opts)))))))

(defn- options
  "The `opts` merged over default-options. Throw when they lack the
  :me-pred, a token store or introspection endpoint, or one of the
  `required` functions."
  [opts required]
  (let [opts (merge-with #(if (map? %1) (merge %1 %2) %2) default-options opts)]
    (when-not (every? #(ifn? (get opts %)) (cons :me-pred required))
      (throw (ex-info (str "A handler needs the functions "
                           (str/join ", " (cons :me-pred required)))
                      {:required (vec (cons :me-pred required))})))
    (when-not (or (:tokens opts) (:introspection-endpoint opts))
      (throw (ex-info "A handler needs the :tokens or the :introspection-endpoint of an IndieAuth server"
                      {})))
    opts))

(defn handler
  "A Ring handler for the Micropub endpoint, with the `opts` below.

  Each function among the `opts` gets the action of a POST or the query of
  a GET, with the :token-info from indieauth-clj (the token's :me,
  :client-id and :scope) and the :ring-request. The access token in the
  Ring request is hidden with wary-fetch's secret/hide, so it doesn't show
  when you print or log the request.

  - :me-pred, a predicate of the :me of a token, e.g. a set of your
    profile URLs
  - :tokens, the token store of an IndieAuth server in the same process,
    or the :introspection-endpoint and :resource-token of one elsewhere
  - :create!, a function of a create that returns the :url of the new
    post, with :status 202 if the post goes live later
  - :source, a function of a map with a :url that returns the entry at
    that URL, or nil
  - :update!, a function of an update with the :entry that apply-update
    made from the entry that :source returned. It returns nil, or the :url
    of a post that moved.
  - :delete! and :undelete!, functions of a delete or an undelete that
    return nil, or the :url of a post that moved
  - :upload!, a function of a file in a multipart create, as Ring's
    multipart middleware has it, that returns the file's URL
  - :config, a map for q=config, or a function of the query that returns
    one. Its :syndicate-to answers q=syndicate-to.
  - :media-endpoint, the URL of the media endpoint, for q=config
  - :queries, a map of other queries by name, e.g. :category, to functions
    of the query that return the answer
  - the :scopes, :insufficient-scope-status and :max-bytes of
    default-options, and the options of indieauth-clj's token checks

  Any function can return a map of the :error instead, a problem as
  problem-response takes it, or in ClojureScript a promise. An action
  without its function gets invalid_request, and an update needs :source
  too (Micropub 2.1.3).

  The handler reads form and JSON bodies itself or takes what middleware
  has read. Multipart bodies need the server's multipart middleware, e.g.
  Ring's, which keeps large files out of memory. The handler takes the
  arguments of both a synchronous and an asynchronous Ring handler."
  [opts]
  (let [opts (options opts [:create!])]
    (ring/handler {:get  #(get-answer % opts)
                   :post #(post-answer % opts)})))

;; Micropub 3.6
(defn- media-answer
  "The answer to a POST `request` to the media endpoint, with `opts`."
  [{:keys [multipart-params] :as request} {:keys [scopes upload!] :as opts}]
  (let [fields (ring/param-fields multipart-params)
        file   (request/field-value fields "file")]
    (async/then (authenticated! request fields opts)
                (fn [info]
                  (cond
                    (:error info)
                    (problem-response (:error info))

                    (not (granted? info (:media scopes)))
                    (problem-response (scope-problem (:media scopes) opts))

                    ;; Micropub 3.6.3: one part named file
                    (not (request/file? file))
                    (problem-response (invalid "The request has no part named file"))

                    :else
                    (async/then (upload! (assoc file
                                                :token-info info
                                                :ring-request (hide-token request)))
                                (fn [result]
                                  (if-let [error (:error result)]
                                    (problem-response error)
                                    ;; Micropub 3.6.4
                                    (ring/response 201 {"Location" result} "")))))))))

(defn media-handler
  "A Ring handler for the media endpoint, with the `opts` of handler.

  It needs the :me-pred, the :tokens or :introspection-endpoint, and the
  :upload!. The file is the part named file of a multipart body, which
  the server's multipart middleware reads, e.g. Ring's. The :upload! gets
  the file with the :token-info and the :ring-request, with its token
  hidden as in handler, and returns its URL or a map of the :error. The handler takes the arguments of both a
  synchronous and an asynchronous Ring handler."
  [opts]
  (let [opts (options opts [:upload!])]
    (ring/handler {:post #(media-answer % opts)})))
