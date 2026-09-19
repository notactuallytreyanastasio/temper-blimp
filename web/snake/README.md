# Snake, in a browser, via Blimp

A snake game written in [Temper][game], compiled to Blimp, run by the Blimp
interpreter compiled to WebAssembly, rendered by a Blimp actor's view tree.

```sh
./build.sh                      # or: GAME=path/to/temper_snake ./build.sh
python3 -m http.server -d . 8000
```

Then `localhost:8000`, and `localhost:8000/selftest.html` to check it without
touching the keyboard.

## The four pieces

| | |
|---|---|
| `snake.blimp` | the game library, `temper build -b blimp` of the game's `src/` — built, not kept |
| `harness.blimp` | an actor that turns it into something a browser can show |
| `blimp.wasm` | the interpreter, `zig build wasm` — built, not kept |
| `index.html` | fetches the three, joins them, hands them to `BlimpView` |

## Why a second runner

The game ships with a runner in `game/run.temper.md`: two `async` blocks, one
reading w/a/s/d from stdin, one ticking every 200ms. A browser has neither
stdin nor a blocking sleep, so that runner cannot work here.

But the *library* — `newGame`, `changeDirection`, `tick`, `render` — knows
nothing about where its output goes. `harness.blimp` is a second runner around
the same four functions, and it does its I/O by describing it:

```
timer(200, :tick)
key("w", :up), key("a", :left), key("s", :down), key("d", :right)
```

Those are view nodes that render nothing. The host reconciles them after every
render: a `timer` becomes a `setInterval` that sends the message, a `key`
becomes an entry in a keydown table. The harness never calls `setTimeout` and
never sees a `KeyboardEvent`; it answers `:tick` and `:up` like any other
message.

None of that machinery is new — `timer` and `key` were already in the
interpreter and already handled by `blimp-view.js`. This is the first thing
that uses them.

## The one thing worth knowing

`render` is 45ms of the frame, and the host asks for the view after *every*
message, including a key press that changes nothing drawn. So the board is
kept in the actor rather than recomputed:

```
app <- :view    48.8ms   ->   4.5ms
```

A live frame is a ~60ms tick and a 5ms view, against a 200ms budget.

## What is not here

No multiplayer, though the library has it: `newMultiGame`, `addPlayer`,
`multiTick` and `multiRender` are all exported and untouched by this harness.
The game's `server/` and `client/` speak WebSocket, which would need `std/ws`
the way the runner needed `std/io`.

[game]: https://github.com/notactuallytreyanastasio/temper_snake
