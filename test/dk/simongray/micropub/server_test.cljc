(ns dk.simongray.micropub.server-test
  "The Micropub endpoint and the media endpoint, with the requests of
  micropub.rocks's server tests. A case from that suite is named by its
  number, and any other by the rule it checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.micropub.server :as server]
            [dk.simongray.micropub.test-support :as t :refer [run]]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]))

(defn- step
  "A step of a test that sends the `request` to the Ring `handler` and
  calls `f` with the response."
  [handler request f]
  #(async/then (handler request) f))

(defn- location
  "The Location header of the `response`."
  [response]
  (get-in response [:headers "Location"]))

(deftest creating-with-forms
  (let [{:keys [posts opts]} (t/site)
        h                    (server/handler opts)]
    (run
     (step h (t/form-request "full-token" [["h" "entry"]
                                           ["content" "Micropub test of creating a basic h-entry"]])
           (fn [r]
             (testing "100"
               (is (= 201 (:status r)))
               (is (= {:type       ["h-entry"]
                       :properties {:content ["Micropub test of creating a basic h-entry"]}}
                      (get @posts (location r)))))))
     (step h (t/form-request "full-token" [["h" "entry"]
                                           ["content" "Two categories"]
                                           ["category[]" "test1"]
                                           ["category[]" "test2"]])
           (fn [r]
             (testing "101"
               (is (= ["test1" "test2"] (get-in @posts [(location r) :properties :category]))))))
     (step h (t/form-request "full-token" [["h" "entry"]
                                           ["content" "A photo referenced by URL"]
                                           ["photo" "https://micropub.rocks/media/sunset.jpg"]])
           (fn [r]
             (testing "104"
               (is (= ["https://micropub.rocks/media/sunset.jpg"]
                      (get-in @posts [(location r) :properties :photo]))))))
     (step h (t/form-request "full-token" [["h" "entry"]
                                           ["content" "One category"]
                                           ["category" "test1"]])
           (fn [r]
             (testing "107"
               (is (= ["test1"] (get-in @posts [(location r) :properties :category]))))))
     (step h (t/form-request "full-token" [["content" "No h"]
                                           ["mp-slug" "no-h"]
                                           ["url" "https://elsewhere.example/"]])
           (fn [r]
             (testing "an h-entry by default, with the mp- commands kept apart (3.2, 3.3)"
               (is (= "https://simon.example/posts/no-h" (location r)))
               (is (= {:type ["h-entry"] :properties {:content ["No h"]}}
                      (get @posts (location r))))))))))

(deftest creating-with-json
  (let [{:keys [posts opts]} (t/site)
        h                    (server/handler opts)
        create               #(t/json-request "full-token" {:type ["h-entry"] :properties %})
        checkin              {:type       ["h-card"]
                              :properties {:name      ["Los Gorditos"]
                                           :latitude  [45.524330801154]
                                           :longitude [-122.68068808051]}}]
    (run
     (step h (create {:content ["Micropub test of creating an h-entry with a JSON request"]})
           (fn [r]
             (testing "200"
               (is (= 201 (:status r)))
               (is (some? (get @posts (location r)))))))
     (step h (create {:content ["Categories"] :category ["test1" "test2"]})
           (fn [r]
             (testing "201"
               (is (= ["test1" "test2"] (get-in @posts [(location r) :properties :category]))))))
     (step h (create {:content [{:html "This post has <b>bold</b> and <i>italic</i> text."}]})
           (fn [r]
             (testing "202"
               (is (= [{:html "This post has <b>bold</b> and <i>italic</i> text."}]
                      (get-in @posts [(location r) :properties :content]))))))
     (step h (create {:content ["A photo"] :photo ["https://micropub.rocks/media/sunset.jpg"]})
           (fn [r]
             (testing "203"
               (is (= ["https://micropub.rocks/media/sunset.jpg"]
                      (get-in @posts [(location r) :properties :photo]))))))
     (step h (create {:published ["2017-05-31T12:03:36-07:00"]
                      :content   ["Lunch meeting"]
                      :checkin   [checkin]})
           (fn [r]
             (testing "204"
               (is (= [checkin] (get-in @posts [(location r) :properties :checkin]))))))
     (step h (create {:content ["Alt text"]
                      :photo   [{:value "https://micropub.rocks/media/sunset.jpg"
                                 :alt   "Photo of a sunset"}]})
           (fn [r]
             (testing "205"
               (is (= [{:value "https://micropub.rocks/media/sunset.jpg" :alt "Photo of a sunset"}]
                      (get-in @posts [(location r) :properties :photo]))))))
     (step h (create {:content ["Two photos"]
                      :photo   ["https://micropub.rocks/media/sunset.jpg"
                                "https://micropub.rocks/media/city-at-night.jpg"]})
           (fn [r]
             (testing "206"
               (is (= 2 (count (get-in @posts [(location r) :properties :photo])))))))
     (step h (t/json-request "full-token" {:type ["h-entry"] :properties {:content "Not an array"}})
           (fn [r]
             (testing "a value that isn't an array (3.3.2)"
               (is (= 400 (:status r)))
               (is (= "invalid_request" (:error (t/body r))))))))))

(deftest creating-with-files
  (let [{:keys [posts files opts]} (t/site)
        h                          (server/handler opts)]
    (run
     (step h (t/multipart-request "full-token" {"h"       "entry"
                                                "content" "A photo"
                                                "photo"   (t/file "sunset.jpg" "image/jpeg")})
           (fn [r]
             (testing "300"
               (is (= 201 (:status r)))
               (is (= ["https://simon.example/media/sunset.jpg"]
                      (get-in @posts [(location r) :properties :photo])))
               (is (= "image/jpeg" (get-in @files ["https://simon.example/media/sunset.jpg"
                                                   :content-type]))))))
     (step h (t/multipart-request "full-token" {"h"       "entry"
                                                "content" "Two photos"
                                                "photo[]" [(t/file "sunset.jpg" "image/jpeg")
                                                           (t/file "city-at-night.jpg" "image/jpeg")]})
           (fn [r]
             (testing "301"
               (is (= ["https://simon.example/media/sunset.jpg"
                       "https://simon.example/media/city-at-night.jpg"]
                      (get-in @posts [(location r) :properties :photo]))))))
     (step (server/handler (dissoc opts :upload!))
           (t/multipart-request "full-token" {"h" "entry" "photo" (t/file "a.jpg" "image/jpeg")})
           (fn [r]
             (testing "files without an :upload!"
               (is (= 400 (:status r))))))
     (step h (assoc (t/form-request "full-token" []) :headers {"content-type" "multipart/form-data; boundary=x"
                                                              "authorization" "Bearer full-token"})
           (fn [r]
             (testing "a multipart body that no middleware read"
               (is (= 400 (:status r)))))))))

(defn- update-test
  "The steps of a micropub.rocks update test with the handler `h`: create
  a post with the `properties`, send the update `ops`, and call `f` with
  the response and the post from the atom `posts`."
  [h posts properties ops f]
  (let [url (atom nil)]
    [(step h (t/json-request "full-token" {:type ["h-entry"] :properties properties})
           #(reset! url (location %)))
     #(async/then (h (t/json-request "full-token" (merge {:action "update" :url @url} ops)))
                  (fn [r] (f r (get @posts @url))))]))

(deftest updating
  (let [{:keys [posts opts]} (t/site)
        h                    (server/handler opts)]
    (apply run
           (concat
            (update-test h posts {:content ["To be replaced"]}
                         {:replace {:content ["This is the updated text."]}}
                         (fn [r post]
                           (testing "400"
                             (is (= 204 (:status r)))
                             (is (= ["This is the updated text."] (get-in post [:properties :content]))))))
            (update-test h posts {:content ["Add"] :category ["test1"]}
                         {:add {:category ["test2"]}}
                         (fn [_ post]
                           (testing "401"
                             (is (= ["test1" "test2"] (get-in post [:properties :category]))))))
            (update-test h posts {:content ["Add a property"]}
                         {:add {:category ["test1"]}}
                         (fn [_ post]
                           (testing "402"
                             (is (= ["test1"] (get-in post [:properties :category]))))))
            (update-test h posts {:content ["Remove a value"] :category ["test1" "test2"]}
                         {:delete {:category ["test2"]}}
                         (fn [_ post]
                           (testing "403"
                             (is (= ["test1"] (get-in post [:properties :category]))))))
            (update-test h posts {:content ["Remove a property"] :category ["test1" "test2"]}
                         {:delete ["category"]}
                         (fn [_ post]
                           (testing "404"
                             (is (= {:content ["Remove a property"]} (:properties post))))))
            (update-test h posts {:content ["Micropub update test #405."]}
                         {:replace "This is not a valid update request."}
                         (fn [r post]
                           (testing "405"
                             (is (= 400 (:status r)))
                             (is (= ["Micropub update test #405."] (get-in post [:properties :content]))))))
            [(step h (t/json-request "full-token" {:action  "update"
                                                   :url     "https://simon.example/posts/none"
                                                   :replace {:content ["x"]}})
                   (fn [r]
                     (testing "an update of a post that doesn't exist"
                       (is (= 400 (:status r))))))
             (step h (t/form-request "full-token" [["action" "update"]
                                                   ["url" "https://simon.example/posts/1"]])
                   (fn [r]
                     (testing "a form-encoded update (3.2)"
                       (is (= 400 (:status r))))))]))))

(deftest deleting
  (let [{:keys [posts trash opts]} (t/site)
        h                          (server/handler opts)
        urls                       (atom [])
        create!                    (step h (t/form-request "full-token" [["content" "To delete"]])
                                         #(swap! urls conj (location %)))]
    (run
     create!
     #(async/then (h (t/form-request "full-token" [["action" "delete"] ["url" (peek @urls)]]))
                  (fn [r]
                    (testing "500"
                      (is (= 204 (:status r)))
                      (is (nil? (get @posts (peek @urls)))))))
     create!
     #(async/then (h (t/json-request "full-token" {:action "delete" :url (peek @urls)}))
                  (fn [r]
                    (testing "501"
                      (is (= 204 (:status r)))
                      (is (some? (get @trash (peek @urls)))))))
     #(async/then (h (t/form-request "full-token" [["action" "undelete"] ["url" (peek @urls)]]))
                  (fn [r]
                    (testing "502"
                      (is (= 204 (:status r)))
                      (is (some? (get @posts (peek @urls)))))))
     #(async/then (h (t/json-request "full-token" {:action "delete" :url (peek @urls)}))
                  (fn [_]
                    (async/then (h (t/json-request "full-token" {:action "undelete" :url (peek @urls)}))
                                (fn [r]
                                  (testing "503"
                                    (is (= 204 (:status r)))
                                    (is (some? (get @posts (peek @urls)))))))))
     (step h (t/json-request "full-token" {:action "delete" :url "https://simon.example/posts/none"})
           (fn [r]
             (testing "the :error of a :delete!"
               (is (= 400 (:status r)))
               (is (= "invalid_request" (:error (t/body r)))))))
     (step (server/handler (dissoc opts :undelete!))
           (t/json-request "full-token" {:action "undelete" :url "https://simon.example/posts/1"})
           (fn [r]
             (testing "an undelete without an :undelete!"
               (is (= 400 (:status r))))))
     (step h (t/json-request "full-token" {:action "delete"})
           (fn [r]
             (testing "a delete without a url"
               (is (= 400 (:status r)))))))))

(deftest querying
  (let [{:keys [opts]} (t/site)
        h              (server/handler opts)
        url            (atom nil)]
    (run
     (step h (t/query-request "full-token" [["q" "config"]])
           (fn [r]
             (testing "600"
               (is (= 200 (:status r)))
               (is (= {:media-endpoint "https://simon.example/media"
                       :syndicate-to   [{:uid  "https://social.example/@simon"
                                         :name "@simon on social.example"}]
                       :post-types     [{:type "note" :name "Note"}]
                       :q              ["config" "syndicate-to" "source" "category"]}
                      (t/body r))))))
     (step h (t/query-request "full-token" [["q" "syndicate-to"]])
           (fn [r]
             (testing "601"
               (is (= 200 (:status r)))
               (is (= "https://social.example/@simon"
                      (get-in (t/body r) [:syndicate-to 0 :uid]))))))
     (step h (t/json-request "full-token" {:type       ["h-entry"]
                                           :properties {:content  ["Test of querying the endpoint for the source content"]
                                                        :category ["micropub" "test"]}})
           #(reset! url (location %)))
     #(async/then (h (t/query-request "full-token" [["q" "source"] ["url" @url]]))
                  (fn [r]
                    (testing "602"
                      (is (= 200 (:status r)))
                      (is (= {:type       ["h-entry"]
                              :properties {:content  ["Test of querying the endpoint for the source content"]
                                           :category ["micropub" "test"]}}
                             (t/body r))))))
     #(async/then (h (t/query-request "full-token" [["q" "source"]
                                                    ["properties[]" "content"]
                                                    ["properties[]" "category"]
                                                    ["url" @url]]))
                  (fn [r]
                    (testing "603"
                      (is (= {:properties {:content  ["Test of querying the endpoint for the source content"]
                                           :category ["micropub" "test"]}}
                             (t/body r))))))
     (step h (t/query-request "full-token" [["q" "source"] ["url" "https://simon.example/posts/none"]])
           (fn [r]
             (testing "the source of a post that doesn't exist (3.8)"
               (is (= 400 (:status r))))))
     (step h (t/query-request "full-token" [["q" "category"]])
           (fn [r]
             (testing "a query of :queries"
               (is (= {:categories ["indieweb" "clojure"]} (t/body r))))))
     (step h (t/query-request "full-token" [["q" "contact"]])
           (fn [r]
             (testing "a query that the server doesn't answer"
               (is (= 400 (:status r))))))
     (step h (t/query-request "profile-token" [["q" "config"]])
           (fn [r]
             (testing "a query needs a Micropub scope"
               (is (= "insufficient_scope" (:error (t/body r))))))))))

(deftest uploading
  (let [{:keys [files opts]} (t/site)
        h                    (server/media-handler opts)
        upload               #(t/multipart-request %1 {"file" (t/file %2 %3)})]
    (run
     (step h (upload "full-token" "sunset.jpg" "image/jpeg")
           (fn [r]
             (testing "700"
               (is (= 201 (:status r)))
               (is (= "image/jpeg" (get-in @files [(location r) :content-type]))))))
     (step h (upload "media-token" "micropub-rocks.png" "image/png")
           (fn [r]
             (testing "701"
               (is (= 201 (:status r)))
               (is (= "https://simon.example/media/micropub-rocks.png" (location r))))))
     (step h (upload "full-token" "w3c-socialwg.gif" "image/gif")
           (fn [r]
             (testing "702"
               (is (= 201 (:status r))))))
     (step h (t/multipart-request "full-token" {"photo" (t/file "a.jpg" "image/jpeg")})
           (fn [r]
             (testing "a request without a part named file (3.6.3)"
               (is (= 400 (:status r))))))
     (step h (upload "update-token" "a.jpg" "image/jpeg")
           (fn [r]
             (testing "an upload needs the media or the create scope"
               (is (= "insufficient_scope" (:error (t/body r))))))))))

(deftest authorizing
  (let [{:keys [posts opts]} (t/site)
        h                    (server/handler opts)
        url                  (atom nil)]
    (run
     (step h (t/form-request "full-token" [["h" "entry"]
                                           ["content" "Testing accepting access token in HTTP Authorization header"]])
           (fn [r]
             (testing "800"
               (is (= 201 (:status r))))))
     (step h (t/form-request nil [["h" "entry"]
                                  ["content" "Testing accepting access token in post body"]
                                  ["access_token" "full-token"]])
           (fn [r]
             (reset! url (location r))
             (testing "801"
               (is (= 201 (:status r))))))
     #(async/then (h (t/query-request "full-token" [["q" "source"] ["url" @url]]))
                  (fn [r]
                    (testing "802"
                      (is (= {:content ["Testing accepting access token in post body"]}
                             (:properties (t/body r)))))))
     (step h (t/form-request nil [["h" "entry"] ["content" "Testing unauthenticated request."]])
           (fn [r]
             (testing "803"
               (is (= 401 (:status r)))
               (is (= "unauthorized" (:error (t/body r))))
               (is (= "Bearer" (get-in r [:headers "WWW-Authenticate"]))))))
     (step h (t/form-request "update-token" [["h" "entry"] ["content" "Testing an unauthorized access token."]])
           (fn [r]
             (testing "804"
               (is (= 401 (:status r)))
               (is (= {:error             "insufficient_scope"
                       :error_description "The access token needs one of the scopes create"
                       :scope             "create"}
                      (t/body r))))))
     (step h (t/form-request "full-token" [["h" "entry"]
                                           ["content" "Testing access token in header and body."]
                                           ["access_token" "full-token"]])
           (fn [r]
             (testing "805"
               (is (= 400 (:status r)))
               (is (= "invalid_request" (:error (t/body r)))))))
     (step h (t/form-request "made-up-token" [["content" "x"]])
           (fn [r]
             (testing "an unknown token"
               (is (= 401 (:status r)))
               (is (= "Bearer error=\"invalid_token\"" (get-in r [:headers "WWW-Authenticate"]))))))
     (step h (t/form-request "visitor-token" [["content" "x"]])
           (fn [r]
             (testing "a token of another user (3.8)"
               (is (= 403 (:status r)))
               (is (= "forbidden" (:error (t/body r)))))))
     (step (server/handler (assoc opts :insufficient-scope-status 403))
           (t/form-request "update-token" [["content" "x"]])
           (fn [r]
             (testing "insufficient_scope as the living standard has it"
               (is (= 403 (:status r))))))
     #(testing "nothing was created without access"
        (is (= 2 (count @posts)))))))

(deftest hidden-tokens
  (let [{:keys [opts]} (t/site)
        seen           (atom [])
        create!        (fn [{:keys [ring-request]}]
                         (swap! seen conj ring-request)
                         {:url "https://simon.example/posts/1"})
        h              (server/handler (assoc opts :create! create!))
        ignore         (fn [_])]
    (run
     (step h (t/form-request "full-token" [["content" "In the header"]]) ignore)
     (step h (t/form-request nil [["content" "In the body"] ["access_token" "full-token"]]) ignore)
     (step h {:request-method :post
              :headers        {"content-type" "application/x-www-form-urlencoded"}
              :form-params    {"content" "Read by middleware" "access_token" "full-token"}}
           ignore)
     #(testing "the Ring request that a function gets doesn't show the token"
        (is (= 3 (count @seen)))
        (is (not-any? (fn [r] (str/includes? (pr-str r) "full-token")) @seen))
        (is (= "Bearer full-token"
               (secret/reveal (get-in (first @seen) [:headers "authorization"]))))
        (is (= "full-token"
               (secret/reveal (get-in (nth @seen 2) [:form-params "access_token"]))))))))

(deftest drafts
  (let [{:keys [posts opts]} (t/site)
        h                    (server/handler opts)
        draft                (atom nil)
        published            (atom nil)]
    (run
     (step h (t/form-request "draft-token" [["content" "A draft"] ["post-status" "published"]])
           (fn [r]
             (reset! draft (location r))
             (testing "a token with the draft scope alone creates drafts"
               (is (= 201 (:status r)))
               (is (= ["draft"] (get-in @posts [(location r) :properties :post-status]))))))
     (step h (t/form-request "full-token" [["content" "Published"]])
           #(reset! published (location %)))
     #(async/then (h (t/json-request "draft-token" {:action  "update"
                                                    :url     @published
                                                    :replace {:content ["Changed"]}}))
                  (fn [r]
                    (testing "and updates only drafts"
                      (is (= "insufficient_scope" (:error (t/body r))))
                      (is (= ["Published"] (get-in @posts [@published :properties :content]))))))
     #(async/then (h (t/json-request "draft-token" {:action  "update"
                                                    :url     @draft
                                                    :replace {:content     ["Changed"]
                                                              :post-status ["published"]}}))
                  (fn [r]
                    (testing "which stay drafts"
                      (is (= 204 (:status r)))
                      (is (= {:content ["Changed"] :post-status ["draft"]}
                             (get-in @posts [@draft :properties]))))))
     (step h (t/json-request "draft-token" {:action "delete" :url "https://simon.example/posts/1"})
           (fn [r]
             (testing "and deletes nothing"
               (is (= "insufficient_scope" (:error (t/body r))))))))))

(deftest requests-and-options
  (let [{:keys [opts]} (t/site)
        h              (server/handler opts)]
    (testing "the options that a handler needs"
      (is (thrown? #?(:clj Exception :cljs :default) (server/handler (dissoc opts :create!))))
      (is (thrown? #?(:clj Exception :cljs :default) (server/handler (dissoc opts :me-pred))))
      (is (thrown? #?(:clj Exception :cljs :default) (server/handler (dissoc opts :tokens))))
      (is (thrown? #?(:clj Exception :cljs :default) (server/media-handler (dissoc opts :upload!)))))
    (testing "an action in each syntax"
      (is (= {:action     :create
              :type       ["h-entry"]
              :properties {:content ["Hello"] :category ["indieweb" "clojure"]}
              :commands   {:mp-slug ["hello"]}}
             (server/action (t/form-request "full-token" [["h" "entry"]
                                                          ["content" "Hello"]
                                                          ["category[]" "indieweb"]
                                                          ["category[]" "clojure"]
                                                          ["mp-slug" "hello"]
                                                          ["access_token" "full-token"]]))))
      (is (= {:action :update :url "https://simon.example/posts/1" :delete [:category]}
             (server/action (t/json-request "full-token" {:action "update"
                                                          :url    "https://simon.example/posts/1"
                                                          :delete ["category"]}))))
      (is (= {:action :create :type ["h-entry"] :properties {:content ["Hello"]}}
             (server/action {:request-method :post
                             :headers        {"content-type" "application/json"}
                             :json-params    {"type"       ["h-entry"]
                                              "properties" {"content" ["Hello"]}}}))))
    (testing "a body larger than :max-bytes"
      (is (= "invalid_request"
             (get-in (server/action (t/form-request nil [["content" "xxxxxxxxxx"]]) {:max-bytes 8})
                     [:error :code]))))
    #?(:clj
       (testing "the arguments of an asynchronous handler"
         (let [p (promise)]
           (h (t/query-request "full-token" [["q" "config"]]) #(deliver p %) #(deliver p %))
           (is (= 200 (:status (deref p 1000 nil)))))))
    (run
     (step h {:request-method :put :headers {}}
           (fn [r]
             (testing "another method (RFC 9110, 15.5.6)"
               (is (= 405 (:status r)))
               (is (= "GET, POST" (get-in r [:headers "Allow"])))))))))
