micropub-clj: a proposal
========================

This proposes micropub-clj (`dk.simongray/micropub-clj`, root namespace
`dk.simongray.micropub`), a Clojure and ClojureScript implementation of
[Micropub](https://www.w3.org/TR/micropub/), the W3C standard with which
apps post to a website. Like webmention-clj and indieauth-clj, it would
come out of indieblog. It would take websub-clj's shape: requests as plain
maps, Ring handlers that take your own functions, nothing running in the
background, safe defaults and the spec's section beside each rule. It would
also be one cljc codebase for Clojure 1.11+ and Java 11+, with the same
results in Node and the browser.

The functions below don't exist yet, so none of the examples has run.


The spec
--------

- **The Recommendation** of 23 May 2017 has no errata. The
  [living standard](https://micropub.spec.indieweb.org/) of 25 July 2020
  changes one rule: insufficient_scope is 403 rather than 401, as in RFC
  6750. Its other changes are editorial.
- **No revision yet.** The
  [Social Web WG](https://www.w3.org/2026/01/social-web-wg-charter.html)
  lists Micropub as a normative deliverable with the 2017 text as its
  start, but with no date and no description. The group maintains the
  spec. Its charter asks for two independent implementations and tests of
  each substantive change. There's no editor's draft beyond the living
  standard, and the w3c/Micropub repository has had only editorial commits
  in 2026. An issue of the extensions
  ([#53](https://github.com/indieweb/micropub-extensions/issues/53),
  February 2026) proposes merging stable extensions into the spec. If that
  happens, a library with a micropub.rocks report is the kind of
  implementation the group would count.
- **[micropub.rocks](https://micropub.rocks/)** tests servers (100 to 805)
  and clients. The site compiles a server's implementation report from the
  tests it passed, while client reports go to w3c/Micropub. The hosted
  site needs a public endpoint and an IndieAuth sign-in (or a token typed
  in). Its tests are open source in
  [aaronpk/micropub.rocks](https://github.com/aaronpk/micropub.rocks), so
  local tests can follow them closely.

The code would follow the Recommendation and cite its sections. Where the
living standard differs, the default follows the Recommendation (test 804
accepts 401 alone) and an option gives 403.


Scope
-----

**In the library:**

- The Micropub endpoint as a Ring handler, with the four actions and the
  queries config, source and syndicate-to. A create can be form-encoded,
  JSON or multipart.
- The media endpoint as a second Ring handler.
- The access checks, through indieauth-clj's resource namespace: the token
  from the header or the form (never both, as test 805 has it), the scope of
  each action and whether the token's `me` may post.
- The update itself, as a pure function from mf2 to mf2.
- A client: endpoint discovery, queries, the four actions and uploads.

**Extensions.** Most need no code: your functions get every property and
every mp- command, and every query that the handler doesn't answer itself.
So q=category, post-types, visibility, mp-slug, mp-syndicate-to,
mp-destination and mp-channel work as plain data. The README would show
the common ones. Two need the library:

- **The draft scope with post-status**
  ([#19](https://github.com/indieweb/micropub-extensions/issues/19),
  [#24](https://github.com/indieweb/micropub-extensions/issues/24)). It's an
  access rule, so it belongs with the others: a token with draft but not
  create may create drafts only, and update drafts only.
- **The list of queries in q=config**
  ([#7](https://github.com/indieweb/micropub-extensions/issues/7)), which
  the handler builds from the queries it answers.

**Left out:**

- Storage, slugs, permalinks and how a post looks. Those are yours.
- Parsing multipart bodies. Files can be large (a podcast episode is), so
  the server's middleware (Ring's or Pedestal's) streams them to temporary
  files and the handler reads its multipart params.
- Fetching a photo URL that a client sends, which §3.3.1 allows but doesn't
  ask for. The endpoint itself never fetches a URL.
- Issuing tokens, which indieauth-clj's server does.
- Post lists (q=source without a url) and the media endpoint's queries,
  whose parameters are still mostly proposals. They can come later.


API
---

- **`dk.simongray.micropub`:** entries and actions as data, and the
  client.
- **`dk.simongray.micropub.server`:** the two handlers and the functions
  they're made of.
- **`dk.simongray.micropub.impl.*`** (all `^:no-doc`): form fields, Ring
  bodies, links and responses.

The dependencies are wary-fetch 0.4.0 for the client's requests and JSON,
html-pieces 0.5.0 for the links of a page, whatwg-url 0.1.0 for URLs and
indieauth-clj for tokens. The library needn't depend on microformats-clj.
An entry has the shape of its output, so you can post an h-event that it
parsed as it is.

### Entries and actions

An entry is mf2 JSON as microformats-clj returns it, with keyword property
names and a vector of values. A value is a string, a map such as an
`{:html …}` content or a photo with `:alt`, or a nested entry:

```clojure
{:type       ["h-entry"]
 :properties {:content  ["Hello"]
              :category ["indieweb" "clojure"]
              :photo    [{:value "https://simon.grays.blog/assets/cat.jpg"
                          :alt   "A cat"}]}}
```

An action is a Micropub request in that shape, whatever its syntax. Form
fields lose their `[]` and become vectors. The `h` field becomes `:type`.
The mp- commands go under `:commands` rather than `:properties` (§3.2),
and the access token never reaches an action. A create is an entry with
an `:action`:

```clojure
;; h=entry&content=Hello&category[]=indieweb&category[]=clojure&mp-slug=hello
{:action     :create
 :type       ["h-entry"]
 :properties {:content  ["Hello"]
              :category ["indieweb" "clojure"]}
 :commands   {:mp-slug ["hello"]}}
```

A multipart create also has `:files` by property, e.g. a `:photo` of Ring's
file maps (`:filename`, `:content-type`, `:tempfile` and `:size`). An update
keeps the keys of its JSON, and its `:delete` is either property names or
values by property (§3.4.3):

```clojure
{:action  :update
 :url     "https://simon.grays.blog/posts/2026/hello"
 :replace {:content ["Hello again"]}
 :add     {:category ["micropub"]}
 :delete  [:location]}
```

A delete or an undelete is a map of `:action` and `:url`. A query holds the
parameters of the GET, e.g. `{:q :source :url "…" :properties [:content]}`.

The update itself is `(micropub/apply-update entry action)`. It applies the
replace, add and delete of the action in that order (§3.4 sets none) and
removes a property that has no values left (§3.4.3).

### The server

```clojure
(require '[dk.simongray.micropub.server :as server])

(def opts
  {:me-pred        #{"https://simon.grays.blog/"}
   :tokens         token-store
   :media-endpoint "https://simon.grays.blog/media"
   :create!        create!
   :update!        update!
   :delete!        delete!
   :source         source
   :upload!        upload!
   :config         {:post-types post-types}
   :queries        {:category categories}})

(def micropub-handler
  (server/handler opts))

(def media-handler
  (server/media-handler opts))
```

The handlers check the token and read the request first. Then each of your
functions gets the action or the query, with the `:token-info` that
indieauth-clj returns (its `:me`, `:client-id` and `:scope`) and the
`:ring-request`:

- `:create!` returns the new post's `:url` for a 201, with `:status 202`
  when the post goes live later (§3.3.6).
- `:source` returns the entry at the `:url`, or nil when there's none. The
  handler answers nil with invalid_request, as the example in §3.8 does,
  and leaves out the properties that a query doesn't ask for.
- `:update!` gets the action with the updated `:entry`, which apply-update
  made from what `:source` returned. It returns nil for a 204, or the new
  `:url` of a post that moved for a 201 (§3.4.4). So you write only the
  mapping in each direction.
- `:delete!` and `:undelete!` return nil for a 204.
- `:upload!` gets a file and returns its URL, which the media handler
  answers with (§3.6.4). In a multipart create, the URL replaces the file
  in the properties, so `:create!` never sees a file.
- `:config` is a map (or a function of the query) for q=config. The handler
  adds the media endpoint and the list of queries. It also answers
  q=syndicate-to with the config's `:syndicate-to` (§3.7.3).
- `:queries` maps each other query to a function that returns its answer,
  e.g. q=category.

Any of them can return an error instead (see below), or a promise in
ClojureScript. Each optional function turns its action or query on. An
update needs `:source`, since §2.1.3 asks for q=source of any server that
updates.

For the tokens, give the `:tokens` store of an indieauth-clj server in the
same process, or the `:introspection-endpoint` and `:resource-token` of one
elsewhere. The handler reads form and JSON bodies itself within
`:max-bytes`, or takes the params that middleware has already parsed.
`server/default-options` lists the other defaults, e.g. the scopes of each
action and the status of insufficient_scope.

If you need another shape, the parts are public: `server/action` and
`server/query` read a Ring request, and `server/problem-response` answers
an error.

### The client

```clojure
(require '[dk.simongray.micropub :as micropub])

(micropub/discover! "https://simon.grays.blog/")
;; => {:status 200 :endpoint "https://simon.grays.blog/micropub"}

(micropub/call! (micropub/query-request endpoint token :config))
;; => {:status 200 :body {:media-endpoint "https://simon.grays.blog/media" …}}

(micropub/call! (micropub/request endpoint token
                                  {:type       ["h-entry"]
                                   :properties {:content ["Hello"]}}))
;; => {:status 201 :url "https://simon.grays.blog/posts/2026/hello"}

(micropub/call! (micropub/upload-request media-endpoint token file))
;; => {:status 201 :url "https://simon.grays.blog/assets/cat.jpg"}
```

The request function takes the same actions that `server/action` returns,
so an action survives the round trip through both. An entry without an
`:action` is a create, as in Micropub. A create goes form-encoded when its
values are text, since every server takes that (§2.1.3), and as JSON
otherwise. Updates are always JSON (§3.4).

The client sends through wary-fetch's `send-public!` unless you give a
`:send` of your own. The token is a hidden text, so it stays out of logs
and behind on a redirect to another site. Discovery reads the Link header
first, as IndieAuth and Webmention do, since §5.3 names no order. Then it
reads only the head of the page: anyone who can write in its body could
otherwise send your posts and your token elsewhere. Give
`micropub/discover` a response that you fetched yourself for the same
answer.


Errors
------

- **Answers.** The handlers answer with the JSON of §3.8 (an error code and
  an error_description) and the status that goes with the code: 400 for
  invalid_request, 401 for unauthorized, 403 for forbidden and 401 for
  insufficient_scope (or 403 by option). A 401 or 403 also has RFC 6750's
  WWW-Authenticate header, as indieauth-clj writes it.
- **Data.** For a request that breaks a rule, `server/action` and
  `server/query` return an error map of `:code` and `:reason`, e.g. for a
  value that isn't an array in an update (test 405). Your functions return
  the same map to refuse an action, e.g. `{:error {:code "invalid_request"
  :reason "No post at that URL"}}`. The client's `call!` returns a server's
  error with its `:status` added, or wary-fetch's failure map when the
  request itself failed.
- **Exceptions.** Only a mistake in your code throws, e.g. a handler
  without `:create!` or `:me-pred`, as in websub-clj.


Tests
-----

- **The scenarios of micropub.rocks, locally.** Each server test becomes a
  Ring request to the handler, over an atom of entries and an indieauth-clj
  token store in memory. A deftest named by the test's number checks the
  status, the Location and what the atom holds. The client tests run the
  client against the same handler.
- **The round trip** of every kind of action through the client's request
  and `server/action`, in form-encoded, JSON and multipart syntax.
- **End to end** on the JVM: the client against both handlers over HTTP on
  localhost, as in websub-clj.
- **Node** runs the same cljc tests with shadow-cljs.
- **The site itself,** by hand. Once indieblog takes the library, run the
  server tests against simon.grays.blog and publish the report. Test 804
  needs a token without the create scope. The client tests need the
  hosted site too, and their report goes to w3c/Micropub. A page like
  webmention-clj's doc/webmention-rocks.md would describe the steps.


indieblog
---------

### What the first reading found

All four are true:

- **Create drops what it doesn't map.** Of the properties, `params->post`
  reads only the name, content, published, category, rsvp, mp-slug and the
  four response properties. Everything else goes, photo and syndication
  (which an update maps) included. §3.3.5 tells a server to ignore what it
  doesn't recognise, so this conforms. But the blog's own media endpoint
  hands a client the URL of a photo that the create then drops. Tests 104,
  203, 205, 206, 300 and 301 of micropub.rocks post photos too.
- **No undelete.** It's deliberate (doc/indieweb.md, section 13) and §3.5
  makes undelete a MAY.
- **q=source ignores properties[].** It also returns only the content,
  published and name, so a client never sees the tags, the response
  properties or the syndication. A server that updates has to answer
  q=source in full (§2.1.3, §3.7.2).
- **No multipart at /micropub.** The multipart interceptor sits on /media
  alone, so a multipart create arrives empty and gets a 400. §2.1.3 asks
  for multipart only of a server without a media endpoint, so this
  conforms too, but tests 300 and 301 fail.

Three more turned up:

- An update of a property that isn't mapped answers 204 and changes
  nothing.
- The endpoint accepts a token sent in both the header and the form, where
  test 805 wants a 400.
- It answers insufficient_scope with 403, where the Recommendation and
  test 804 want 401.

### One mapping

The idea holds. With the library, `params->post` and
`update-property->attr` become one table of properties and frontmatter
keys, read in both directions. A create writes an entry, q=source reads
one, and an update reads the post as an entry and writes back what
apply-update makes of it. Three things keep that round trip from losing
anything:

- **Read the file, not the db post.** The db derives a title from a post's
  first heading (`expand-post`). The q=source answer already has that
  title as the name, and an update that wrote it back would put it in the
  frontmatter beside the heading it came from.
- **Keep the frontmatter keys that the table lacks,** e.g. location and
  language. Today's `edit-attr` keeps them because it edits the
  frontmatter in place, so the writer should merge into the old
  frontmatter rather than replace it.
- **Keep the permalink.** The date's year and the slug fix the URL, which
  is why an update ignores published and mp-slug today. After the round
  trip, an update that changes them has to be refused or ignored, since
  moving the file breaks the old URL.

### What it deletes and keeps

From micropub.clj, indieblog deletes:

- the token checks, `request-token` and `authorize`
- the JSON responses and their error codes
- `first-val` and the syntax half of `params->post`
- `update-property->attr`, `edit-attr` and `apply-update`
- the six handle- functions, whose file operations move into the
  functions it gives the handler

The micropub and media functions of the interceptors namespace become the
library's two handlers, made with one map of options. It keeps:

- the table, and the reading and writing of post files
  (`frontmatter-block`, `parse-file`, `write-post!` and `list-attrs`)
- `derive-slug`, `unique-slug` and `url->year+slug`
- the naming and storing of uploads, and the logging
- the data of q=config and q=category

That's about half of the namespace. The authorize test in auth_test.clj
would call the handler instead. The blog has no Micropub tests today, so
the table should get round-trip tests of its own.


Other users
-----------

- **The events app.** Today indieblog refuses every type but h-entry. The
  handler passes any type on, so an h-event with a nested location reaches
  `:create!` as it is, and a post type in the config can offer events to
  clients.
- **A podcast host.** An episode would be an entry with an audio URL from
  the media endpoint, which the host turns into an item of its feed. The
  "defaults for a producer writing a feed from plain data" in podcast-clj's
  needs would do most of that. Episodes are large, which is one reason
  multipart parsing stays with the server's middleware. The client's
  upload, though, holds its file in memory.


Size
----

About 1,000 lines of source and as many of tests: less than webmention-clj
(1,200) and websub-clj (1,600). That's a few days of work, 3 on the
ranking's scale. The server takes about half of it.

Version 0.2.0 of indieauth-clj still depends on wary-fetch 0.3.0,
html-pieces 0.4.1 and microformats-clj 0.2.0. It needs a release on the
versions above before micropub-clj can depend on it. With the second pick
below, wary-fetch needs one too.


Open questions
--------------

Each question has my pick and its reason.

1. **insufficient_scope.** Should the default be the Recommendation's 401
   or the living standard's 403?
   *Pick:* 401, with 403 as an option. It's what the Recommendation says
   and what test 804 accepts, so the implementation report comes out
   clean. Then ask micropub.rocks, in an issue, to accept 403 as well, and
   switch the default if it does.
2. **Uploads in wary-fetch.** Should wary-fetch get a `multipart-request`
   or should micropub-clj build the body itself?
   *Pick:* wary-fetch, beside `form-request` and `json-request`. It's the
   third kind of body that a request can post, and any upload to an API
   needs it. That means a wary-fetch release before the client.
3. **Shared discovery.** Should the reading of links in headers and pages
   move to a shared library first, as webmention-clj's discovery namespace
   suggests?
   *Pick:* not yet. Most of the code is calls to wary-fetch's Link headers
   and html-pieces' links. Neither library depends on the other, so a
   shared home means a new dependency and releases of three libraries.
   micropub-clj keeps an impl copy of about 30 lines meanwhile.
4. **Update by entry.** Should the handler also take an `:update!` that
   gets the operations alone, for a store that changes properties in place
   (e.g. SQL)?
   *Pick:* no, not until such a store turns up. The planned users keep
   files or documents, the entry lets the handler enforce the draft rule
   on updates, and a second option can come later without breaking
   anything.
5. **The mapping in indieblog.** Which dropped properties should a create
   keep? Should an update that moves a post be refused or ignored? Should
   /micropub take multipart creates?
   *Pick:* keep photo, syndication and a location that's plain text. A
   photo becomes a Markdown image in the body, with its alt text, as in a
   hand-written post. Syndication goes in the frontmatter where an update
   already puts it. Nested values are still ignored, as §3.3.5 allows.
   Refuse an update that changes the year or the slug with
   invalid_request: the blog never moves a permalink, and the client
   should know its change didn't happen. Take multipart creates too: it
   costs one interceptor and the `:upload!` that /media needs anyway, and
   it passes tests 300 and 301. Undelete stays out, as the blog decided.
