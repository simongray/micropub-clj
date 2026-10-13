micropub.rocks
==============

[micropub.rocks](https://micropub.rocks) tests Micropub servers and
clients over the internet. You sign in with your domain, and it sends
your endpoint the requests of each server test, or takes your client's
requests at an endpoint of its own. The tests of this library reproduce
its server tests locally, as Ring requests to the handlers with the
posts kept in memory, so that they run in CI on both platforms. A test
names each scenario by its number, e.g. 804.

Most server tests have two parts. The site checks the answer, e.g. its
status and Location. Then you look at your post and tick a box if it's
right, e.g. if it shows both photos. The local tests check the answer
and the entry that your functions get, but not what your site shows.

Covered
-------

All the server tests, in `server_test.cljc`:

- **Creating:** 100, 101, 104 and 107 (form-encoded), 200 to 206 (JSON)
  and 300 and 301 (multipart). Each also checks the entry that
  `:create!` gets, e.g. the photos with their alt text, the HTML content
  and the nested object.
- **Updating:** 400 to 405, which also check the post after the update.
- **Deleting:** 500 to 503. The undeletes need an `:undelete!`; without
  one, an undelete gets a 400.
- **Querying:** 600 to 603.
- **The media endpoint:** 700 to 702.
- **Authorization:** 800 to 805. Test 804 accepts only a 401 for a token
  without the scope, so that's the default, though the living standard
  has 403. Set `:insufficient-scope-status` to 403 to follow it.

Not covered
-----------

- **What your site shows.** The boxes that you tick after looking at
  your post, e.g. whether it renders the HTML rather than escaping it,
  depend on what your functions store and what your templates show.
- **Your files.** Tests 700 to 702 fetch the URL that the media endpoint
  returns, and check that the file comes back with its content type.
- **The client tests.** The client's own tests check its requests, read
  them back with `server/action` and post to the endpoint over HTTP, but
  they don't follow the client tests of micropub.rocks. The client
  doesn't upload files yet, so it can't pass client tests 300, 301 and
  700.
