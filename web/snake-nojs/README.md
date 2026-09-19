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
| `temper/src/webapp.temper.md` | routing, game state, the HTML, the CSS |
| `server.blimp` | accept a socket, read a request line, write a response |

`build.sh` prints the split: about 6,600 lines generated against 70 written.
The written ones do nothing a browser can see. Every byte of the page comes
out of Temper, through `temper build -b blimp`.

That is further than `../snake` goes. There the harness is hand-written Blimp,
because it produces a *view tree* and view nodes are Blimp builtins Temper
cannot name. A page of HTML is only a `String`, so Temper can build it, and
routing is only a comparison of one, so Temper can do that too.

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

No favicon — a browser asking for one gets the board, because `handle` returns
the current state for any path it does not know. That is the right answer for
a typo and the wrong one for `/favicon.ico`, and telling them apart needs a
404 this does not have.
