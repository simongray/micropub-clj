micropub-clj
============

This is a Clojure and ClojureScript implementation of
[Micropub](https://www.w3.org/TR/micropub/), the W3C standard with which
apps post to a website, e.g. a note from your phone or a photo from a
desktop app. It covers both sides: the endpoint and the media endpoint of
a site, and a client that posts to them.

- **The standard.** The code follows the Recommendation of 23 May 2017
  and cites the section of each rule it implements. Its tests include the
  scenarios of the [micropub.rocks](https://micropub.rocks) test suite,
  run locally.
- **Plain data.** Every request becomes a map in the shape of
  microformats2 JSON, whatever its syntax. The endpoints are Ring
  handlers that call your own functions.
- **Safe by default.** The endpoints check access tokens with
  [indieauth-clj](https://github.com/simongray/indieauth-clj) and never
  fetch a URL. The request your functions get hides its token, so your
  logs don't show it. The client finds an endpoint only in the head of a
  page, where a commenter can't add one.

The same code runs on the JVM, in Node and in the browser.

> This library was spun out of
> [indieblog](https://github.com/simongray/indieblog), which runs
> [simon.grays.blog](https://simon.grays.blog) and takes posts from
> Micropub clients. It was developed with assistance from frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. It isn't on Clojars yet, so use
the SHA of the latest commit on `master`:

```clojure
dk.simongray/micropub-clj
{:git/url "https://github.com/simongray/micropub-clj"
 :git/sha "…"}
```

For ClojureScript, shadow-cljs only reads Git dependencies from
`deps.edn`, so also set `:deps true` in your `shadow-cljs.edn`.

### Run an endpoint

An endpoint is a Ring handler. Give it the access tokens of an IndieAuth
server and functions that store your posts:

```clojure
(require '[dk.simongray.micropub.server :as server])

(def posts
  (atom {}))

(defn create!
  "Store the entry of the create `action` and return the new post's URL."
  [{:keys [type properties] :as action}]
  (let [url (str "https://simon.grays.blog/posts/" (inc (count @posts)))]
    (swap! posts assoc url {:type type :properties properties})
    {:url url}))

(def opts
  {:me-pred #{"https://simon.grays.blog/"}
   :tokens  tokens
   :create! create!
   :source  (fn [{:keys [url]}] (get @posts url))
   :update! (fn [{:keys [url entry]}] (swap! posts assoc url entry) nil)
   :delete! (fn [{:keys [url]}] (swap! posts dissoc url) nil)})

(def endpoint
  (server/handler opts))
```

The `:tokens` are the token store of an indieauth-clj server in the same
process. For a server elsewhere, give its `:introspection-endpoint` and a
`:resource-token` instead. The `:me-pred` says whose tokens may post.

The handler checks the token and its scope, then calls your function
with an action like this one, plus the `:token-info` and the
`:ring-request`:

```clojure
{:action     :create
 :type       ["h-entry"]
 :properties {:content  ["Hello"]
              :category ["indieweb" "clojure"]}
 :commands   {:mp-slug ["hello"]}}
```

A `:create!` returns the new post's `:url`, and the handler answers 201.
For an update, the handler reads the post with `:source`, applies the
update with `micropub/apply-update` and passes the result to `:update!`
as the `:entry`. That way you only map entries to your posts and back,
and the same `:source` answers clients that edit posts.

To refuse an action, return an error such as `{:error {:code
"invalid_request" :reason "No post at that URL"}}`. The handler answers
with Micropub's JSON.

Name the endpoint in a Link header of your home page, or in its head:

```html
<link rel="micropub" href="https://simon.grays.blog/micropub">
```

### Files

Clients upload photos and other files to a media endpoint. Make one from
the same options and an `:upload!` that stores a file and returns its
URL:

```clojure
(require '[clojure.java.io :as io])

(defn upload!
  "Store the `file` under a random name and return its URL."
  [{:keys [tempfile] :as file}]
  (let [name (str (random-uuid))]
    (io/copy tempfile (io/file "media" name))
    (str "https://simon.grays.blog/media/" name)))

(def media-endpoint
  (server/media-handler (assoc opts :upload! upload!)))
```

Clients find it in q=config, so give its URL to the Micropub endpoint as
the `:media-endpoint`. The handlers don't parse multipart bodies
themselves, so give your server multipart middleware such as Ring's
`wrap-multipart-params`, which keeps large files out of memory. Given an
`:upload!`, the Micropub endpoint also takes multipart creates. It
uploads each file with `:upload!` and puts the URL in the file's place.

### Extensions

Your functions get every property and mp- command as data, so most
extensions (e.g. post-status, visibility and mp-slug) need nothing from
the library. Put the post types and syndication targets for q=config in
`:config`, and answer other queries with `:queries`:

```clojure
(assoc opts
       :config  {:post-types   [{:type "note" :name "Note"}]
                 :syndicate-to []}
       :queries {:category (fn [query] {:categories ["indieweb" "clojure"]})})
```

A token with only the draft scope can create and update drafts, as the
[draft scope extension](https://github.com/indieweb/micropub-extensions/issues/24)
proposes. The other defaults, e.g. the scopes of each action, are in
`server/default-options`.

### Post from a client

Find a site's endpoint, and send it actions with the access token you got
from IndieAuth:

```clojure
(require '[dk.simongray.micropub :as micropub])

(micropub/discover! "https://simon.grays.blog/")
;; => {:status 200 :endpoint "https://simon.grays.blog/micropub"}

(micropub/call! (micropub/request "https://simon.grays.blog/micropub" token
                                  {:type       ["h-entry"]
                                   :properties {:content ["Hello"]}}))
;; => {:status 201 :url "https://simon.grays.blog/posts/2026/hello"}
```

The client sends a create form-encoded when it can, since every server
accepts that, and as JSON otherwise. For a query, e.g. q=config to find
the media endpoint, use `micropub/query-request`. A server's error comes
back under `:error`, with Micropub's `:code` and `:reason`. The client
doesn't upload files yet.

Principles
----------

- **Standards first.** The code follows the Recommendation and cites its
  sections, and a comment marks each choice of its own. Where the living
  standard differs, an option follows it.
- **Plain data.** Actions, queries and answers are maps, and the client
  sends through any `:send` you give it.
- **One codebase.** The library is written in `.cljc` and depends on
  [wary-fetch](https://github.com/simongray/wary-fetch) for requests and
  JSON, [html-pieces](https://github.com/simongray/html-pieces) for pages,
  [whatwg-url](https://github.com/simongray/whatwg-url) for URLs,
  [whatwg-mime](https://github.com/simongray/whatwg-mime) for media types
  and indieauth-clj for tokens.

Development
-----------

```bash
clojure -X:test                       # the tests on the JVM
npm install                           # once, for the Node tests
clojure -M:cljs compile test          # the tests in Node
clojure -M:cljs compile browser-test  # for a browser, from target/browser-test
```

On the JVM, a test also runs the client against the endpoint over HTTP on
localhost. [doc/micropub-rocks.md](doc/micropub-rocks.md) says which tests
of micropub.rocks the tests cover.

License
-------

The micropub-clj project is licensed under the [MIT licence](LICENSE).
