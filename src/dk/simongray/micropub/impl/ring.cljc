(ns ^:no-doc dk.simongray.micropub.impl.ring
  "Reading Ring requests and writing Ring responses."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            #?(:clj [dk.simongray.wary-fetch.async :as async])
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.json :as json]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.whatwg-mime :as mime])
  #?(:clj (:import [java.io InputStream])))

(defn header
  "The first value of the header `header-name` of the Ring `request`."
  [request header-name]
  (headers/header (:headers request) header-name))

(defn media-type
  "The media type of the body of the Ring `request`, in lower case and
  without its parameters, or nil when it has none that parses."
  [request]
  (mime/essence (mime/extract (get (:headers request) "content-type"))))

(defn body-bytes
  "The bytes of a Ring request's `body` (a string, bytes, a stream or nil),
  or nil when it holds more than `max-bytes`. In ClojureScript a stream
  returns nil too, so a server there must read the body first."
  [body max-bytes]
  (let [b (cond
            (nil? body)
            (bytes/encode "")

            (string? body)
            (bytes/encode body)

            :else
            #?(:clj  (if (bytes? body)
                       body
                       (.readNBytes ^InputStream body (int (inc max-bytes))))
               :cljs (when (instance? js/Uint8Array body) body)))]
    (when (and b (<= (bytes/length b) max-bytes))
      b)))

(defn param-fields
  "The Ring `params` map as pairs of a name and a value. Its keys are
  strings or keywords, and its values are single values or vectors."
  [params]
  (for [[k v] params
        v     (if (sequential? v) v [v])]
    [(name k) v]))

(defn query-fields
  "The query parameters of the Ring `request` as pairs of a name and a
  value, from its :query-string or else its :query-params."
  [{:keys [query-string query-params] :as request}]
  (if query-string
    (url/query-pairs query-string)
    (param-fields query-params)))

(defn form-fields
  "The form fields that the Ring `request` posts, as pairs of a name and a
  value: its :multipart-params or :form-params when middleware has read
  them, or else those of a body of at most `max-bytes`. Nil when the body
  is larger."
  [{:keys [multipart-params form-params body] :as request} max-bytes]
  (cond
    (seq multipart-params) (param-fields multipart-params)
    (seq form-params)      (param-fields form-params)
    :else                  (some-> (body-bytes body max-bytes)
                                   bytes/decode
                                   url/query-pairs)))

(defn json-body
  "The JSON that the Ring `request` posts, with keyword keys: the map that
  middleware has read (e.g. :json-params), or else the JSON of a body of
  at most `max-bytes`. Nil when the body is larger or isn't JSON."
  [{:keys [json-params body-params body] :as request} max-bytes]
  (if-let [m (some #(when (map? %) %) [json-params body-params body])]
    (walk/keywordize-keys m)
    (some-> (body-bytes body max-bytes) bytes/decode json/read-json)))

(defn response
  "The Ring response of `status` with the `headers` and the text `body`."
  [status headers body]
  {:status status :headers headers :body body})

(defn json-response
  "The Ring response of `status` with the JSON of `x`."
  [status x]
  (response status {"Content-Type" "application/json"} (json/write-json x)))

;; RFC 9110, section 15.5.6
(defn- by-method
  "A function that answers a Ring request with the function for its method
  in `fns`, or with 405 and the allowed methods."
  [fns]
  (let [allow (->> (keys fns) (map (comp str/upper-case name)) sort (str/join ", "))]
    (fn [{:keys [request-method] :as request}]
      (if-let [f (get fns request-method)]
        (f request)
        (response 405 {"Allow" allow} "")))))

(defn handler
  "A Ring handler that answers each request with the function for its
  method in `fns`, which returns a response or a promise of one. Other
  methods get 405. It takes the arguments of both a synchronous and an
  asynchronous handler."
  [fns]
  (let [f (by-method fns)]
    (fn
      ([request]
       (f request))
      ([request respond raise]
       #?(:clj  (let [{:keys [answer error]} (try
                                               {:answer (async/then (f request) identity)}
                                               (catch Throwable e
                                                 {:error e}))]
                  (if error
                    (raise error)
                    (respond answer)))
          :cljs (-> (js/Promise.resolve nil)
                    (.then (fn [_] (f request)))
                    (.then respond)
                    (.catch raise)))))))
