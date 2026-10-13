(ns dk.simongray.micropub.end-to-end-test
  "The client and the Micropub endpoint of this library, which talk over
  HTTP on localhost: discovery from a profile page, a create, a source
  query, an update and a delete."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.micropub :as micropub]
            [dk.simongray.micropub.server :as server]
            [dk.simongray.micropub.test-support :as t]
            [dk.simongray.wary-fetch :as http]))

(def anywhere
  "The options of requests to localhost."
  {:allow-pred (constantly true)
   :send       http/send!})

(defn- profile
  "A Ring handler of a profile page whose head names the Micropub endpoint
  at /micropub."
  [_]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    "<html><head><link rel=\"micropub\" href=\"/micropub\"></head><body></body></html>"})

(deftest a-client-and-a-server
  (let [{:keys [posts opts]} (t/site)]
    (t/with-server
     {"/"         profile
      "/micropub" (server/handler opts)}
     (fn [base]
       (let [{:keys [status endpoint]} (micropub/discover! (str base "/") anywhere)
             call!                     #(micropub/call! % anywhere)]
         (testing "discovery"
           (is (= 200 status))
           (is (= (str base "/micropub") endpoint)))
         (let [{:keys [url]} (call! (micropub/request endpoint "full-token"
                                                      {:type       ["h-entry"]
                                                       :properties {:content  ["Hello"]
                                                                    :category ["indieweb" "clojure"]}}))]
           (testing "a create"
             (is (= "https://simon.example/posts/1" url))
             (is (= {:type       ["h-entry"]
                     :properties {:content ["Hello"] :category ["indieweb" "clojure"]}}
                    (get @posts url))))
           (testing "a source query"
             (is (= {:status 200
                     :body   {:properties {:category ["indieweb" "clojure"]}}}
                    (call! (micropub/query-request endpoint "full-token" :source
                                                   {:url url :properties [:category]})))))
           (testing "an update"
             (is (= {:status 204}
                    (call! (micropub/request endpoint "full-token"
                                             {:action :update :url url :delete {:category ["indieweb"]}}))))
             (is (= ["clojure"] (get-in @posts [url :properties :category]))))
           (testing "a delete"
             (is (= {:status 204}
                    (call! (micropub/request endpoint "full-token" {:action :delete :url url}))))
             (is (empty? @posts)))
           (testing "an error"
             (is (= {:status 401 :code "unauthorized"}
                    (select-keys (:error (call! (micropub/query-request endpoint nil :config)))
                                 [:status :code]))))))))))
