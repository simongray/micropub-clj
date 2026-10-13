(ns dk.simongray.micropub.end-to-end-test
  "The client and the endpoints of this library, which talk over HTTP on
  localhost: discovery from a profile page, a create, a source query, an
  update and a delete, and files, read by Ring's multipart middleware."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.micropub :as micropub]
            [dk.simongray.micropub.server :as server]
            [dk.simongray.micropub.test-support :as t]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.webcrypto.bytes :as bytes]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]))

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

(deftest files
  (let [{:keys [posts files opts]} (t/site)
        photo                      {:content      (bytes/of [0xff 0xd8 0xff 0xe0])
                                    :filename     "cat.jpg"
                                    :content-type "image/jpeg"}]
    (t/with-server
     {"/micropub" (wrap-multipart-params (server/handler opts))
      "/media"    (wrap-multipart-params (server/media-handler opts))}
     (fn [base]
       (let [call!  #(micropub/call! % anywhere)
             stored #(let [{:keys [content-type tempfile]} (get @files "https://simon.example/media/cat.jpg")]
                       [content-type (vec (.readAllBytes (java.io.FileInputStream. ^java.io.File tempfile)))])]
         (testing "an upload to the media endpoint (3.6)"
           (is (= {:status 201 :url "https://simon.example/media/cat.jpg"}
                  (call! (micropub/upload-request (str base "/media") "full-token" photo))))
           (is (= ["image/jpeg" [-1 -40 -1 -32]] (stored))))
         (reset! files {})
         (testing "a create with a file (3.3.1)"
           (let [{:keys [url]} (call! (micropub/request (str base "/micropub") "full-token"
                                                        {:type       ["h-entry"]
                                                         :properties {:content ["A cat"]}
                                                         :files      {:photo [photo]}}))]
             (is (= {:content ["A cat"] :photo ["https://simon.example/media/cat.jpg"]}
                    (get-in @posts [url :properties])))
             (is (= ["image/jpeg" [-1 -40 -1 -32]] (stored))))))))))
