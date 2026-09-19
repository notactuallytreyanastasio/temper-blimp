#!/bin/sh
# Produces the two files index.html fetches but does not keep:
#
#   snake.blimp  the game library, compiled from Temper
#   blimp.wasm   the interpreter
#
# The game itself lives in another repository; point GAME at a checkout of it,
# or let this clone one into a temporary directory.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
game=${GAME:-}

if [ -z "$game" ]; then
  game=$(mktemp -d)/temper_snake
  git clone --depth 1 https://github.com/notactuallytreyanastasio/temper_snake "$game"
fi

# Only `src/` is built. `game/`, `server/` and `client/` are runners, and the
# harness beside this script is a fourth one.
work=$(mktemp -d)
cp -R "$game/src" "$work/src"
( cd "$work" && "$root/temper/cli/build/install/temper/bin/temper" build -b blimp )
cp "$work/temper.out/blimp/snake/main.blimp" "$here/snake.blimp"

( cd "$root/blimp/chunks/lang" && zig build wasm )
cp "$root/blimp/chunks/lang/zig-out/web/blimp.wasm" "$here/blimp.wasm"

cp "$root/blimp/chunks/lang/web/blimp.js" "$here/blimp.js"
cp "$root/blimp/chunks/lang/web/blimp-view.js" "$here/blimp-view.js"

echo "built:"
ls -la "$here/snake.blimp" "$here/blimp.wasm"
echo
echo "now: python3 -m http.server -d $here 8000"
