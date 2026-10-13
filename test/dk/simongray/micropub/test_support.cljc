(ns dk.simongray.micropub.test-support
  "What the tests share:

  - a test of steps that return promises in ClojureScript
  - a site with its access tokens and posts in memory
  - the Ring requests of micropub.rocks's tests
  - a :send for the client that calls a Ring handler, and on the JVM a
    server of Ring handlers on localhost"
  (:require #?(:cljs [clojure.test :refer [is async]])
            [clojure.string :as str]
            [dk.simongray.indieauth.digest :as digest]
            [dk.simongray.indieauth.server :as indieauth-server]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.json :as json]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.whatwg-url :as whatwg-url])
  #?(:clj (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
                   [java.net InetSocketAddress])))

(defn run
  "Run the steps `fs` one after another as a test on either platform. Each
  step is a function of no arguments that returns a value or a promise.

  In ClojureScript, a deftest runs only the async test that it returns. So
  a deftest calls `run` once, as its last form, with all its steps."
  [& fs]
  #?(:clj  (doseq [f fs] (f))
     :cljs (async done
             (-> (reduce (fn [p f] (.then p (fn [_] (f)))) (js/Promise.resolve nil) fs)
                 (.catch (fn [e] (is false (str "rejected: " e))))
                 (.finally done)))))

;;; The site

(def me
  "The profile URL of the site's owner."
  "https://simon.example/")

(def token-scopes
  "The scope of each access token in the store. All but the visitor's
  token are the owner's."
  {"full-token"    "create update delete undelete"
   "update-token"  "update"
   "media-token"   "media"
   "draft-token"   "draft"
   "profile-token" "profile"
   "visitor-token" "create"})

(defn tokens
  "An IndieAuth token store with the tokens of token-scopes."
  []
  (let [store (indieauth-server/memory-store)]
    (doseq [[token scope] token-scopes]
      ((:put! store) (digest/text token)
                     {:type      :access
                      :me        (if (= "visitor-token" token)
                                   "https://visitor.example/"
                                   me)
                      :client-id "https://app.example/"
                      :scope     scope}))
    store))

(defn site
  "A site with its posts and files in atoms, as a map of the atoms and the
  :opts of its handlers. A deleted post moves to the :trash."
  []
  (let [posts (atom {})
        trash (atom {})
        files (atom {})
        n     (atom 0)]
    {:posts posts
     :trash trash
     :files files
     :opts  {:me-pred        #{me}
             :tokens         (tokens)
             :media-endpoint "https://simon.example/media"
             :create!        (fn [{:keys [type properties commands]}]
                               (let [url (str me "posts/" (or (first (:mp-slug commands))
                                                              (swap! n inc)))]
                                 (swap! posts assoc url {:type type :properties properties})
                                 {:url url}))
             :source         (fn [{:keys [url]}]
                               (get @posts url))
             :update!        (fn [{:keys [url entry]}]
                               (swap! posts assoc url entry)
                               nil)
             :delete!        (fn [{:keys [url]}]
                               (if-let [entry (get @posts url)]
                                 (do (swap! trash assoc url entry)
                                     (swap! posts dissoc url)
                                     nil)
                                 {:error {:code   "invalid_request"
                                          :reason (str "No post at " url)}}))
             :undelete!      (fn [{:keys [url]}]
                               (when-let [entry (get @trash url)]
                                 (swap! posts assoc url entry)
                                 (swap! trash dissoc url)
                                 nil))
             :upload!        (fn [{:keys [filename] :as file}]
                               (let [url (str me "media/" filename)]
                                 (swap! files assoc url (dissoc file :token-info :ring-request))
                                 url))
             :config         {:syndicate-to [{:uid "https://social.example/@simon"
                                              :name "@simon on social.example"}]
                              :post-types   [{:type "note" :name "Note"}]}
             :queries        {:category (fn [_]
                                          {:categories ["indieweb" "clojure"]})}}}))

;;; Requests

(defn- authorized
  "The `headers` with the access `token` in the Authorization header, if
  there's one."
  [headers token]
  (cond-> headers
    token (assoc "authorization" (str "Bearer " token))))

(defn form-request
  "The Ring request that posts the form `fields`, pairs of a name and a
  value, with the access `token`."
  [token fields]
  {:request-method :post
   :headers        (authorized {"content-type" "application/x-www-form-urlencoded; charset=utf-8"}
                               token)
   :body           (url/query-string fields)})

(defn json-request
  "The Ring request that posts the JSON of `x` with the access `token`."
  [token x]
  {:request-method :post
   :headers        (authorized {"content-type" "application/json"} token)
   :body           (json/write-json x)})

(defn multipart-request
  "The Ring request of a multipart form with the access `token`, and the
  `params` as Ring's multipart middleware reads them."
  [token params]
  {:request-method   :post
   :headers          (authorized {"content-type" "multipart/form-data; boundary=x"} token)
   :multipart-params params})

(defn query-request
  "The Ring request of a query with the access `token` and the `params`,
  pairs of a name and a value."
  [token params]
  {:request-method :get
   :headers        (authorized {} token)
   :query-string   (url/query-string params)})

(defn file
  "A file of a multipart form with the `filename` and `content-type`, as
  Ring's multipart middleware reads it."
  [filename content-type]
  {:filename filename :content-type content-type :size 3 :bytes [1 2 3]})

(defn body
  "The JSON of the body of the Ring `response`, or nil."
  [response]
  (some-> (:body response) not-empty json/read-json))

;;; The client and a server

(defn ring-request-of
  "The Ring request that a server makes of the wary-fetch `request`."
  [{:keys [method url headers body] :as request}]
  {:request-method (or method :get)
   :uri            (whatwg-url/pathname url)
   :query-string   (some-> (whatwg-url/search url) not-empty (subs 1))
   :headers        (into {} (for [[k v] headers
                                  :when (some? v)]
                              [(str/lower-case k) (secret/reveal v)]))
   :body           (secret/reveal body)})

(defn sender
  "A :send for the client that answers each request with the Ring
  `handler`, without a network."
  [handler]
  (fn [request]
    (async/then (handler (ring-request-of request))
                (fn [{:keys [status headers body]}]
                  {:status  status
                   :url     (:url request)
                   :headers (update-keys headers str/lower-case)
                   :body    body}))))

#?(:clj
   (defn- ring-request
     "The Ring request of the JDK server's `exchange`."
     [^HttpExchange exchange]
     (let [uri (.getRequestURI exchange)]
       {:request-method (keyword (str/lower-case (.getRequestMethod exchange)))
        :uri            (.getPath uri)
        :query-string   (.getRawQuery uri)
        :scheme         :http
        :headers        (into {}
                              (for [[k vs] (.getRequestHeaders exchange)]
                                [(str/lower-case k) (str/join ", " vs)]))
        :body           (.getRequestBody exchange)})))

#?(:clj
   (defn- respond!
     "Answer the JDK server's `exchange` with the Ring `response`."
     [^HttpExchange exchange {:keys [status headers body]}]
     (let [^bytes b (if (bytes? body) body (.getBytes (str body) "UTF-8"))]
       (doseq [[k v] headers]
         (.add (.getResponseHeaders exchange) (str k) (str v)))
       ;; with the request body open, Java 11 closes the connection after
       ;; an answer with no body, and the client reuses it
       (.close (.getRequestBody exchange))
       (if (zero? (alength b))
         (.sendResponseHeaders exchange status -1)
         (do (.sendResponseHeaders exchange status (alength b))
             (with-open [out (.getResponseBody exchange)]
               (.write out b))))
       (.close exchange))))

#?(:clj
   (defn with-server
     "Call `f` with the URL of a server on localhost that answers each
     request by the Ring handler that `handlers`, a map of paths, has for
     its path, or with 404."
     [handlers f]
     (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
       (.createContext server "/"
                       (reify HttpHandler
                         (handle [_ exchange]
                           (let [request (ring-request exchange)
                                 h       (get handlers (:uri request))]
                             (respond! exchange
                                       (if h
                                         (h request)
                                         {:status 404 :headers {} :body ""}))))))
       (.start server)
       (try
         (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
         (finally
           (.stop server 0))))))
