(ns ^:no-doc dk.simongray.micropub.impl.request
  "Reading the actions and queries of Micropub requests from their form
  fields or JSON (Micropub 3.1 to 3.7)."
  (:require [clojure.string :as str]))

(defn problem
  "A map of the invalid_request :error with the `reason`."
  [reason]
  {:error {:code "invalid_request" :reason reason}})

;; Micropub 3.1.1: a field with several values has a name that ends in []
(defn field-name
  "The form field `name` without a [] at its end."
  [name]
  (str/replace name #"\[\]$" ""))

(defn file?
  "Whether `v`, the value of a multipart field, is a file, which Ring's
  multipart middleware reads as a map."
  [v]
  (map? v))

;; Micropub 3.2
(defn command?
  "Whether the property `k` is a command to the server, which starts with
  mp-."
  [k]
  (str/starts-with? (name k) "mp-"))

(defn field-value
  "The value of the first field named `field` among the form `fields`, or
  nil."
  [fields field]
  (some (fn [[k v]] (when (= field k) v)) fields))

;;; Form fields

;; Micropub 3.2: the reserved fields and the rest, which are properties
(defn- with-field
  "The `action` with the value `v` of the form field `k` added."
  [action [k v]]
  (let [k (field-name k)]
    (case k
      "h"            (assoc action :type [(str "h-" v)])
      "action"       (assoc action :action v)
      "url"          (assoc action :url v)
      "access_token" action
      (update-in action
                 [(cond (file? v) :files (command? k) :commands :else :properties)
                  (keyword k)]
                 (fnil conj [])
                 v))))

;; Micropub 3.3
(defn- form-create
  "The create of the form fields read into `action`, an h-entry by
  default."
  [{:keys [type] :as action}]
  (if (and type (str/blank? (subs (first type) 2)))
    (problem "The h field is empty")
    (cond-> (-> action (dissoc :url) (assoc :action :create))
      (not type) (assoc :type ["h-entry"]))))

;; Micropub 3.5
(defn- with-url
  "The action of `kind`, a delete or an undelete, of the :url of `action`,
  or a problem when it has none."
  [kind {:keys [url] :as action}]
  (if (and (string? url) (not (str/blank? url)))
    {:action kind :url url}
    (problem "A delete or an undelete needs the url of the post")))

(defn form-action
  "The action of the form `fields`, pairs of a name and a value, or a map
  of the :error when they aren't valid."
  [fields]
  (let [action (reduce with-field {} fields)]
    (case (:action action)
      (nil "create") (form-create (dissoc action :action))
      "delete"       (with-url :delete action)
      "undelete"     (with-url :undelete action)
      ;; Micropub 3.2: an update can't be form-encoded
      "update"       (problem "An update is sent as JSON")
      (problem (str "Unknown action: " (:action action))))))

;;; JSON

;; Micropub 3.3.2 and 3.4: each value is an array
(defn- values?
  "Whether `m` is a map of properties to arrays of values."
  [m]
  (and (map? m) (every? sequential? (vals m))))

(defn- vectors
  "The map `m` of properties to arrays of values, with vectors for the
  arrays."
  [m]
  (update-vals m vec))

;; Micropub 3.3.2
(defn- json-create
  "The create of the JSON object `m`, an h-entry by default."
  [{:keys [type properties] :as m}]
  (cond
    (not (or (nil? type) (and (sequential? type) (every? string? type))))
    (problem "The type must be an array of strings")

    (not (or (nil? properties) (values? properties)))
    (problem "The properties must be an object of arrays")

    :else
    (let [{commands true properties false} (group-by (comp command? key) properties)]
      (cond-> {:action     :create
               :type       (vec (or (seq type) ["h-entry"]))
               :properties (vectors (into {} properties))}
        commands (assoc :commands (vectors (into {} commands)))))))

;; Micropub 3.4.3: the names of whole properties, or values by property
(defn- deletions?
  "Whether `x` is the delete of an update."
  [x]
  (or (and (sequential? x) (every? string? x))
      (values? x)))

;; Micropub 3.4
(defn- json-update
  "The update of the JSON object `m`."
  [{:keys [url add delete] :as m}]
  (let [replacements (:replace m)]
    (cond
      (not (and (string? url) (seq url)))
      (problem "An update needs the url of the post")

      (not (every? #(or (nil? %) (values? %)) [replacements add]))
      (problem "The replace and the add of an update must be objects of arrays")

      (not (or (nil? delete) (deletions? delete)))
      (problem "The delete of an update must be an array of property names or an object of arrays")

      :else
      (cond-> {:action :update :url url}
        replacements (assoc :replace (vectors replacements))
        add          (assoc :add (vectors add))
        delete       (assoc :delete (if (map? delete)
                                      (vectors delete)
                                      (mapv keyword delete)))))))

(defn json-action
  "The action of the JSON value `x`, or a map of the :error when it isn't
  valid."
  [x]
  (if-not (map? x)
    (problem "The JSON isn't an object")
    (case (:action x)
      (nil "create") (json-create x)
      "update"       (json-update x)
      "delete"       (with-url :delete x)
      "undelete"     (with-url :undelete x)
      (problem (str "Unknown action: " (:action x))))))

;;; Queries

;; Micropub 3.7
(defn- with-param
  "The `query` with the value `v` of the parameter `k` added."
  [query [k v]]
  (let [param (field-name k)]
    (cond
      (= "q" param)            (assoc query :q (some-> v not-empty keyword))
      (= "access_token" param) query
      (= "properties" param)   (update query :properties (fnil conj []) (keyword v))
      (not= param k)           (update query (keyword param) (fnil conj []) v)
      :else                    (assoc query (keyword param) v))))

(defn query
  "The query of the parameters `fields`, pairs of a name and a value: a
  map of the :q, the :properties of a source query and the other
  parameters by name, with a vector of values for a name that ends in []."
  [fields]
  (reduce with-param {} fields))
