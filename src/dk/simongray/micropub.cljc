(ns dk.simongray.micropub
  "Micropub entries and actions as data, and a client that discovers an
  endpoint and sends it actions and queries.

  An entry is microformats2 JSON as microformats-clj parses it: a map of
  its :type, e.g. [\"h-entry\"], and its :properties, a map of keywords to
  vectors of values. An action is a Micropub request in the same shape, as
  the server namespace reads it, e.g.

      {:action  :update
       :url     \"https://simon.grays.blog/posts/2026/hello\"
       :replace {:content [\"Hello again\"]}
       :delete  [:location]}

  The code follows the W3C Recommendation of 23 May 2017 at
  https://www.w3.org/TR/micropub/ and cites its sections."
  (:require [clojure.string :as str]
            [dk.simongray.micropub.impl.discovery :as discovery]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.json :as json]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.whatwg-url :as whatwg-url]))

(def default-options
  "The defaults of the client's options:

  - :body-links?, false: discovery reads only the head of a page, since
    anyone who can write in its body could name another endpoint and get
    your posts and your token
  - :send, http/send-public!, which sends only to the public internet
  - :timeout-seconds, 10, and :max-bytes, 2 MB, which limit each request"
  {:body-links?     false
   :send            http/send-public!
   :timeout-seconds 10
   :max-bytes       (* 2 1024 1024)})

;;; Updates

;; Micropub 3.4.1
(defn- replace-values
  "The `properties` with the values of each property in `replacements`
  replaced by the new ones."
  [properties replacements]
  (reduce-kv (fn [m k vs] (assoc m k (vec vs))) properties replacements))

;; Micropub 3.4.2
(defn- add-values
  "The `properties` with the values in `additions` added after their own."
  [properties additions]
  (reduce-kv (fn [m k vs] (update m k (fnil into []) vs)) properties additions))

;; Micropub 3.4.3
(defn- remove-values
  "The `properties` without the `deletions`: either a vector of the
  properties to remove, or a map of properties to the values to remove."
  [properties deletions]
  (if (map? deletions)
    (reduce-kv (fn [m k vs]
                 (cond-> m
                   (contains? m k) (update k #(vec (remove (set vs) %)))))
               properties
               deletions)
    (apply dissoc properties deletions)))

;; Micropub 3.4.3: "If no values remain, the property is removed."
(defn- without-empty
  "The `properties` without those that have no values."
  [properties]
  (into {} (remove (comp empty? val)) properties))

(defn apply-update
  "The `entry` with the update `action` applied: its :replace first, then
  its :add and then its :delete, an order that Micropub 3.4 leaves open. A
  property left without values is removed."
  [entry {replacements :replace additions :add deletions :delete :as action}]
  (update entry :properties #(-> (or % {})
                                 (replace-values replacements)
                                 (add-values additions)
                                 (remove-values deletions)
                                 (without-empty))))

;;; Discovery

;; Micropub 5.3
(defn discover
  "The Micropub endpoint that the `response` to a profile URL names, as a
  map of the :endpoint, or nil when it names none. The `opts` are those of
  default-options, e.g. :body-links?.

  Micropub sets no order, so the Link headers come before the page, as in
  IndieAuth and Webmention. A relative URL resolves against the URL of the
  response or the base of the page."
  ([response]
   (discover response {}))
  ([response opts]
   (let [{:keys [body-links?]} (merge default-options opts)]
     (when-let [endpoint (first (discovery/urls response "micropub" body-links?))]
       {:endpoint endpoint}))))

(defn discover!
  "Fetch the profile page at `url` with `opts` and return a map of its
  :status and the :endpoint that discover finds. A failed fetch returns a
  map of the :error from http/failure. In ClojureScript, it returns a
  promise.

  The `opts` are those of default-options and http/send!."
  ([url]
   (discover! url {}))
  ([url opts]
   (let [{:keys [send] :as opts} (merge default-options opts)
         request                 {:url     url
                                  :headers {"Accept" (str "text/html, application/xhtml+xml;q=0.9, "
                                                          "*/*;q=0.1")}}]
     (async/then (http/try-send! send request opts)
                 (fn [{:keys [value error]}]
                   (if error
                     {:error (http/failure error)}
                     (merge {:status (:status value)}
                            (when (<= 200 (:status value) 299)
                              (discover value opts)))))))))

;;; Requests

(defn- texts?
  "Whether every value in `m`, a map of properties to values, is a string."
  [m]
  (every? string? (mapcat val m)))

(defn- form?
  "Whether the create `action` can be form-encoded, with one type and only
  strings as values."
  [{:keys [type properties commands] :as action}]
  (and (= 1 (count type))
       (str/starts-with? (first type) "h-")
       (texts? properties)
       (texts? commands)))

;; Micropub 3.1.1: a field with several values has [] after its name
(defn- fields-of
  "The form fields of `m`, a map of properties to values."
  [m]
  (for [[k vs] m
        v      vs]
    [(cond-> (name k) (next vs) (str "[]")) v]))

;; Micropub 3.3
(defn- create-fields
  "The form fields of the create `action`."
  [{:keys [type properties commands] :as action}]
  (concat [["h" (subs (first type) 2)]]
          (fields-of properties)
          (fields-of commands)))

;; Micropub 3.3.2: the mp- commands are properties in JSON
(defn- create-json
  "The JSON object of the create `action`, an h-entry by default."
  [{:keys [type properties commands] :as action}]
  {:type       (or (not-empty type) ["h-entry"])
   :properties (merge properties commands)})

;; Micropub 3.4
(defn- update-json
  "The JSON object of the update `action`."
  [{:keys [url add delete] :as action}]
  (cond-> {:action "update" :url url}
    (:replace action) (assoc :replace (:replace action))
    add               (assoc :add add)
    delete            (assoc :delete delete)))

(defn- request-options
  "The options of a POST with the access `token`, which follows no
  redirects."
  [token]
  {:headers       {"Authorization" (http/authorization {:token token})}
   :max-redirects 0})

(defn request
  "The request map that posts the `action` to the Micropub `endpoint` with
  the access `token` and `opts`.

  An action without :action is a create, as in Micropub 3.3, so you can
  post an entry as it is. The body is:

  - form-encoded for a create with one type and only strings as values,
    since every server accepts that (2.1.3), unless :json? is true in
    `opts`
  - JSON for any other create and for an update (3.3.2, 3.4)
  - form-encoded for a delete or an undelete (3.5)

  You can hide the token with wary-fetch's secret/hide. The Authorization
  header is hidden either way."
  ([endpoint token action]
   (request endpoint token action {}))
  ([endpoint token {kind :action :keys [url files] :or {kind :create} :as action}
    {:keys [json?] :as opts}]
   (let [options (request-options token)]
     (cond
       (seq files)
       (throw (ex-info "The client doesn't send files yet" {:action action}))

       (and (= :create kind) (form? action) (not json?))
       (http/form-request endpoint (create-fields action) options)

       (= :create kind)
       (http/json-request endpoint (create-json action) options)

       (not (and (string? url) (seq url)))
       (throw (ex-info "An update, delete or undelete needs the :url of the post"
                       {:action action}))

       (= :update kind)
       (http/json-request endpoint (update-json action) options)

       (#{:delete :undelete} kind)
       (http/form-request endpoint [["action" (name kind)] ["url" url]] options)

       :else
       (throw (ex-info (str "Unknown action: " kind) {:action action}))))))

;; Micropub 3.7: the parameters of a query go after those of the endpoint
(defn query-request
  "The request map that sends the query `q`, e.g. :config or :source, with
  the `params` and the access `token` to the Micropub `endpoint`.

  The `params` are a map, e.g. of the :url and :properties of a source
  query. A vector becomes one parameter for each value, with [] after the
  name."
  ([endpoint token q]
   (query-request endpoint token q {}))
  ([endpoint token q params]
   (let [value  #(if (keyword? %) (name %) (str %))
         fields (for [[k v] params
                      x     (if (sequential? v) v [v])]
                  [(cond-> (name k) (sequential? v) (str "[]")) (value x)])]
     {:method  :get
      :url     (url/with-query endpoint (cons ["q" (name q)] fields))
      :headers {"Authorization" (http/authorization {:token token})
                "Accept"        "application/json"}})))

;;; Answers

;; Micropub 3.3.6, 3.4.4, 3.5.1, 3.7 and 3.8
(defn- answer
  "The server's answer in the `response`: a map of its :status, the :url
  in its Location header and its JSON :body, or a map of the :error when
  the status isn't a success."
  [{:keys [status headers body url] :as response}]
  (let [json     (when (and (string? body) (seq body)) (json/read-json body))
        location (some-> (headers/header headers "location")
                         ((whatwg-url/resolver url)))]
    (if (<= 200 status 299)
      (cond-> {:status status}
        location     (assoc :url location)
        (some? json) (assoc :body json))
      {:error (cond-> {:status status
                       :reason (or (:error_description json)
                                   (str "The server answered " status))}
                (:error json) (assoc :code (:error json)))})))

(defn call!
  "Send the `request` of an action or a query with `opts`, and return the
  server's answer: a map of its :status, the :url of a post that was
  created or moved, and the JSON :body of a query. In ClojureScript, it
  returns a promise.

  A status that isn't a success returns a map of the :error, with the
  :status and Micropub's :code and :reason (3.8). A request that fails
  returns the :error from http/failure. The `opts` are those of
  default-options and http/send!."
  ([request]
   (call! request {}))
  ([request opts]
   (let [{:keys [send] :as opts} (merge default-options opts)]
     (async/then (http/try-send! send request opts)
                 (fn [{:keys [value error]}]
                   (if error
                     {:error (http/failure error)}
                     (answer value)))))))

(comment
  (apply-update {:type       ["h-entry"]
                 :properties {:content  ["Hello"]
                              :category ["indieweb" "clojure"]}}
                {:action  :update
                 :url     "https://simon.grays.blog/posts/2026/hello"
                 :replace {:content ["Hello again"]}
                 :add     {:category ["micropub"]}
                 :delete  {:category ["indieweb"]}})
  ;; => {:type       ["h-entry"]
  ;;     :properties {:content  ["Hello again"]
  ;;                  :category ["clojure" "micropub"]}}
  #_.)
