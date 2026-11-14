# Testing a built app — what is real, what is not

The question this file answers: when the agent says "the app works", was the
app actually opened and used, or did it just run unit tests?

## What runs today

`web_test(project, script)`:

1. the project folder is served over **loopback** (`LocalAppServer`, 127.0.0.1,
   OS-assigned port, closed when the test ends). Loopback rather than
   `file://` because `fetch`, ES modules, service workers and absolute paths
   only behave as they will for a user when served;
2. the page is loaded in a **real Chromium WebView** inside Vorv;
3. the model's script runs **inside the page**: it clicks, types, reads the
   DOM and asserts;
4. console JavaScript errors are collected for the whole run;
5. a PNG of the rendered page is written next to the project;
6. `WebVerdict` decides pass/fail by rule.

## The rules (a run fails when any of these hold)

- the script's answer could not be parsed,
- the script threw,
- **zero** checks ran,
- any check failed,
- the page logged a JavaScript error.

"No result" is never a pass. That rule exists precisely because a broken app
that reports nothing is how a broken app gets handed over.

## Verified here, and how

- `LocalAppServerTest` (8 tests): serving, nested paths, index fallback,
  content types, `no-store`, traversal refused in both raw and percent-encoded
  form, 404 names the path, close really stops the socket.
- `WebVerdictTest` (9 tests): pass case, escaped-string unwrap, failing check
  fails a page that claims `ok`, console error fails a clean run, empty checks
  fail, `null`/blank fails, thrown script reported, garbage rejected, report
  names each failing check.
- **Live**: a small todo app was written, served by `LocalAppServer`, opened in
  a real browser and exercised — typing, adding items, clicking an item to
  toggle it, and refusing an empty add. 7 of 7 in-page checks passed and the
  rendered page is what a user would see.

## The honest limit

This proves **web apps** (HTML/CSS/JS/PWA). It does **not** prove a native
Android app, because:

- building an APK on the phone needs `aapt2` + `d8` on a JVM the phone can
  load, which does not exist yet (see `OWN-COMPUTER.md`);
- driving another app's UI needs accessibility privileges, which the user has
  to grant and which this app does not request.

So the app must not claim "tested" for a native Android build. For those, the
project is produced with a GitHub Actions workflow that builds and runs the
tests on a real runner, and the user gets the artifact — that is a server, not
the phone, and it is said plainly rather than implied.

## Why unit tests are not enough on their own

Unit tests prove the logic in isolation. They cannot catch: a button wired to
the wrong handler, a missing `id`, a script that 404s, a page that throws on
load, or CSS that hides the content. Every one of those is invisible to a unit
test and obvious to `web_test`.
