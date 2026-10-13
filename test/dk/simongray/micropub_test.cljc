(ns dk.simongray.micropub-test
  "Updating entries, and the client: discovery, requests and the server's
  answers."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.micropub :as micropub]
            [dk.simongray.micropub.server :as server]
            [dk.simongray.micropub.test-support :as t :refer [run]]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(def entry
  {:type       ["h-entry"]
   :properties {:content  ["Hello"]
                :category ["indieweb" "clojure"]}})

(deftest updates
  (testing "replace (3.4.1)"
    (is (= ["Hello again"]
           (get-in (micropub/apply-update entry {:replace {:content ["Hello again"]}})
                   [:properties :content])))
    (is (= ["2026-10-13"]
           (get-in (micropub/apply-update entry {:replace {:published ["2026-10-13"]}})
                   [:properties :published]))
        "a property that the entry lacks"))
  (testing "add (3.4.2)"
    (is (= ["indieweb" "clojure" "micropub"]
           (get-in (micropub/apply-update entry {:add {:category ["micropub"]}})
                   [:properties :category])))
    (is (= ["https://simon.example/assets/cat.jpg"]
           (get-in (micropub/apply-update entry {:add {:photo ["https://simon.example/assets/cat.jpg"]}})
                   [:properties :photo]))))
  (testing "delete (3.4.3)"
    (is (= ["clojure"]
           (get-in (micropub/apply-update entry {:delete {:category ["indieweb"]}})
                   [:properties :category]))
        "values")
    (is (= {:content ["Hello"]}
           (:properties (micropub/apply-update entry {:delete [:category]})))
        "whole properties")
    (is (= {:content ["Hello"]}
           (:properties (micropub/apply-update entry {:delete {:category ["indieweb" "clojure"]
                                                               :location ["Copenhagen"]}})))
        "a property left without values, and one that the entry lacks"))
  (testing "replace, then add, then delete"
    (is (= {:content  ["Hello again"]
            :category ["clojure" "micropub"]}
           (:properties (micropub/apply-update entry {:replace {:content ["Hello again"]}
                                                      :add     {:category ["micropub"]}
                                                      :delete  {:category ["indieweb"]}}))))
    (is (= {:content ["Hello"]}
           (:properties (micropub/apply-update entry {:replace {:category []}})))
        "a replace with no values"))
  (testing "an entry without properties"
    (is (= {:type ["h-entry"] :properties {:content ["Hi"]}}
           (micropub/apply-update {:type ["h-entry"]} {:add {:content ["Hi"]}})))))

(defn- page
  "The response of a profile page with the `html` and the Link `header`."
  [html header]
  {:status  200
   :url     "https://simon.example/about"
   :headers (cond-> {"content-type" "text/html; charset=utf-8"}
              header (assoc "link" header))
   :body    html})

(deftest discovery
  (testing "a link element, resolved against the page (5.3)"
    (is (= {:endpoint "https://simon.example/micropub"}
           (micropub/discover (page "<link rel=\"micropub\" href=\"/micropub\">" nil)))))
  (testing "the Link header before the page"
    (is (= {:endpoint "https://simon.example/from-header"}
           (micropub/discover (page "<link rel=\"micropub\" href=\"/micropub\">"
                                    "</from-header>; rel=\"micropub\"")))))
  (testing "the head of the page alone, unless :body-links?"
    (let [html "<head></head><body><link rel=\"micropub\" href=\"/body\"></body>"]
      (is (nil? (micropub/discover (page html nil))))
      (is (= {:endpoint "https://simon.example/body"}
             (micropub/discover (page html nil) {:body-links? true})))))
  (testing "link types in any case, among others"
    (is (= {:endpoint "https://simon.example/micropub"}
           (micropub/discover (page "<link rel=\"me MICROPUB\" href=\"/micropub\">" nil)))))
  (testing "a page that names no endpoint"
    (is (nil? (micropub/discover (page "<link rel=\"webmention\" href=\"/wm\">" nil))))))

(def endpoint
  "The Micropub endpoint of the test site."
  "https://simon.example/micropub")

(deftest requests
  (testing "a create of strings is form-encoded (2.1.3, 3.1.1)"
    (let [r (micropub/request endpoint "full-token" {:type       ["h-entry"]
                                                     :properties {:content  ["Hello"]
                                                                  :category ["a" "b"]}
                                                     :commands   {:mp-slug ["hello"]}})]
      (is (= "application/x-www-form-urlencoded" (get-in r [:headers "Content-Type"])))
      (is (= "h=entry&content=Hello&category%5B%5D=a&category%5B%5D=b&mp-slug=hello"
             (secret/reveal (:body r))))
      (is (= "Bearer full-token" (secret/reveal (get-in r [:headers "Authorization"]))))
      (is (= 0 (:max-redirects r)))))
  (testing "and any other create is JSON (3.3.2)"
    (is (= "application/json"
           (get-in (micropub/request endpoint "t" {:properties {:content [{:html "<b>Hi</b>"}]}})
                   [:headers "Content-Type"])))
    (is (= "application/json"
           (get-in (micropub/request endpoint "t" {:properties {:content ["Hi"]}} {:json? true})
                   [:headers "Content-Type"]))))
  (testing "the other actions"
    (is (= "application/json"
           (get-in (micropub/request endpoint "t" {:action :update :url "https://simon.example/p" :add {:category ["x"]}})
                   [:headers "Content-Type"])))
    (is (= "action=delete&url=https%3A%2F%2Fsimon.example%2Fp"
           (secret/reveal (:body (micropub/request endpoint "t" {:action :delete :url "https://simon.example/p"})))))
    (is (thrown? #?(:clj Exception :cljs :default)
                 (micropub/request endpoint "t" {:action :delete})))
    (is (thrown? #?(:clj Exception :cljs :default)
                 (micropub/request endpoint "t" {:action :publish :url "https://simon.example/p"}))))
  (testing "a query's parameters go after the endpoint's own (3.7)"
    (is (= (str "https://simon.example/micropub?site=1&q=source&url=https%3A%2F%2Fsimon.example%2Fp"
                "&properties%5B%5D=content&properties%5B%5D=category")
           (:url (micropub/query-request "https://simon.example/micropub?site=1" "t" :source
                                         {:url "https://simon.example/p" :properties [:content :category]}))))))

(deftest round-trips
  (testing "an action through the client's request and server/action"
    (doseq [action [{:action     :create
                     :type       ["h-entry"]
                     :properties {:content ["Hello"] :category ["a" "b"]}
                     :commands   {:mp-slug ["hello"]}}
                    {:action     :create
                     :type       ["h-event"]
                     :properties {:name     ["A meeting"]
                                  :location [{:type       ["h-card"]
                                              :properties {:name ["Copenhagen"]}}]}}
                    {:action     :create
                     :type       ["h-entry"]
                     :properties {:content [{:html "<b>Hi</b>"}]
                                  :photo   [{:value "https://simon.example/cat.jpg" :alt "A cat"}]}
                     :commands   {:mp-syndicate-to ["https://social.example/"]}}
                    {:action  :update
                     :url     "https://simon.example/p"
                     :replace {:content ["Hi"]}
                     :add     {:category ["x"]}
                     :delete  {:category ["y"]}}
                    {:action :update :url "https://simon.example/p" :delete [:category]}
                    {:action :delete :url "https://simon.example/p"}
                    {:action :undelete :url "https://simon.example/p"}]]
      (is (= action (server/action (t/ring-request-of (micropub/request endpoint "t" action))))
          (str (:action action))))))

(deftest answers
  (let [{:keys [opts]} (t/site)
        send           (t/sender (server/handler opts))
        call!          #(micropub/call! % {:send send})]
    (run
     #(async/then (call! (micropub/request endpoint "full-token" {:properties {:content ["Hello"]}}))
                  (fn [a]
                    (testing "a create"
                      (is (= {:status 201 :url "https://simon.example/posts/1"} a)))))
     #(async/then (call! (micropub/query-request endpoint "full-token" :source
                                                 {:url "https://simon.example/posts/1"}))
                  (fn [a]
                    (testing "a query"
                      (is (= {:status 200
                              :body   {:type ["h-entry"] :properties {:content ["Hello"]}}}
                             a)))))
     #(async/then (call! (micropub/request endpoint "full-token" {:action  :update
                                                                  :url     "https://simon.example/posts/1"
                                                                  :replace {:content ["Hi"]}}))
                  (fn [a]
                    (testing "an update"
                      (is (= {:status 204} a)))))
     #(async/then (call! (micropub/request endpoint "made-up" {:properties {:content ["Hello"]}}))
                  (fn [a]
                    (testing "an error of the server (3.8)"
                      (is (= {:error {:status 401
                                      :code   "unauthorized"
                                      :reason "The access token is unknown, revoked or expired"}}
                             a)))))
     #(async/then (micropub/call! (micropub/query-request endpoint "t" :config)
                                  {:send (fn [_] (throw (ex-info "No network" {})))})
                  (fn [a]
                    (testing "a request that failed"
                      (is (= "No network" (get-in a [:error :reason])))))))))
