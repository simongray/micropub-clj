(ns ^:no-doc dk.simongray.micropub.impl.discovery
  "The URLs that a response links with a link type, in its Link headers
  and the link elements of its HTML page.

  Nothing here is particular to Micropub. The same code is in
  webmention-clj, websub-clj and indieauth-clj, and it could move to a
  library that they share."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.whatwg-url :as url]))

;; RFC 8288, section 3.3: link types separated by whitespace, in any case
(defn relation?
  "Whether `rel` is one of the link types in `s`, a list of them separated
  by whitespace, in any case."
  [rel s]
  (boolean (some #{rel} (str/split (str/lower-case (str s)) #"[\t\n\f\r ]+"))))

;; RFC 8288, section 3
(defn header-urls
  "The URLs in the Link headers of the response `headers` with the link
  type `rel`, in order and resolved against `base`, the response's URL."
  [headers base rel]
  (let [absolute (url/resolver base)]
    (keep (fn [link]
            (when (relation? rel (:rel link))
              (absolute (:href link))))
          (headers/links headers))))

(defn- html?
  "Whether the `response` is an HTML page."
  [{:keys [headers body] :as response}]
  (and (string? body)
       (contains? #{nil "text/html" "application/xhtml+xml"}
                  (headers/media-type (headers/header headers "content-type")))))

(defn page-urls
  "The URLs of the link elements with the link type `rel` in the head of
  the HTML `page`, or anywhere in it when `body?`, resolved against its
  base element or `base`."
  [page base rel body?]
  (map (comp :href second)
       (html/links page {:base-url  base
                         :link-tags #{:link}
                         :rel       rel
                         :body?     (boolean body?)})))

(defn urls
  "The URLs that the `response` links with the link type `rel`: those in
  its Link headers first, then those in its HTML page, as page-urls reads
  it with `body?`."
  [{:keys [url headers body] :as response} rel body?]
  (distinct (concat (header-urls headers url rel)
                    (when (html? response)
                      (page-urls body url rel body?)))))
