# Snake, served, with no script on the page

The same Temper game as `../snake`, reaching a browser a different way: a
Blimp process serves HTML, and there is no JavaScript anywhere in it.

```sh
./build.sh                # or: GAME=path/to/temper_snake ./build.sh
blimp app.blimp           # http://localhost:8099
```

```
$ curl -s http://localhost:8099/ | grep -c '<script'
0
```

1637 bytes a page, and a browser plays it to tick 10 and into the wall on
`<meta http-equiv="refresh">` alone.

## Where the line is

| | |
|---|---|
| `temper/src/webapp.temper.md` | the accept loop, the request parsing, the HTTP framing, the routing, the HTML, the CSS |
| `server.blimp` | `serve(8099)` |

`build.sh` prints the split. It used to be seventy-one hand-written lines; it
is one.

The five socket calls Temper could not say are `std/serve` now -- `listen`,
`Listener.accept`, `Connection.request`, `Connection.respond` -- declared the
way `std/net` declares the other half of the same idea, and implemented for
this backend in temper-core. Nothing in that interface is a file descriptor,
so a backend is free to answer it over something that is not TCP.

Everything else was only ever string work. A request line is a `String` and
splitting one is not something a language needs help with:

```temper
let pathOf(raw: String): String {
  let firstLine = raw.split("\r\n").getOr(0, "GET / HTTP/1.1");
  let target = firstLine.split(" ").getOr(1, "/");
  target.split("?").getOr(0, "/")
}
```

That is further than `../snake` can go. There the harness has to be
hand-written Blimp, because it produces a *view tree* and view nodes are Blimp
builtins Temper has no way to name.

## The clock and the controls

Both are HTML.

```html
<meta http-equiv="refresh" content="0.25;url=/tick">
<a href="/w" accesskey="w">up</a>
```

The refresh is the game loop. Every quarter second the browser asks for
`/tick`, `WebApp.handle` advances the game and returns a new one, and the
response is the next frame. When the game ends the tag stops being emitted and
the page goes still — a finished game should not keep asking for frames.

The arrows are ordinary links. `accesskey` gives them a key each, though which
modifier you hold to reach it is the browser's business, not the page's.

## What that costs

**One game, for everybody.** State lives in the `Session` actor because a page
with no script has nowhere else to keep it. Two browsers pointed at this share
a snake. Putting the game in a cookie or the URL would fix it and neither is
here.

**A history entry per frame.** A meta refresh is a navigation, so playing for
ten seconds leaves forty of them. Back goes to the previous frame, not the
previous page.

**A quarter second, not 200ms.** Below about 250ms the refresh and the round
trip start to overlap, and the browser is still fetching the last frame when
it is due to ask for the next.

**Keys only through a modifier.** There is no `keydown` without JavaScript.
`accesskey` is the closest HTML gets.

## What is not here

No favicon -- a browser asking for one gets the board, because `handle`
returns the current state for any path it does not know. That is the right
answer for a typo and the wrong one for `/favicon.ico`, and telling them apart
needs a 404 this does not have.

One connection at a time, served to completion before the next is accepted.
`std/serve` has no shape for concurrency and this does not need one.
