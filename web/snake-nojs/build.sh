#!/bin/sh
# Produces app.blimp: the generated library, then the server.
#
# The game lives in another repository; point GAME at a checkout, or let this
# clone one into a temporary directory.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
game=${GAME:-}

if [ -z "$game" ]; then
  game=$(mktemp -d)/temper_snake
  git clone --depth 1 https://github.com/notactuallytreyanastasio/temper_snake "$game"
fi

# One work root holding two libraries: the game's `src/`, and the `web/` in
# this directory that imports it.
work=$(mktemp -d)
cp -R "$game/src" "$work/src"
cp -R "$here/temper" "$work/web"
( cd "$work" && "$root/temper/cli/build/install/temper/bin/temper" build -b blimp )

cat "$work/temper.out/blimp/snake-web/main.blimp" "$here/server.blimp" > "$here/app.blimp"

generated=$(wc -l < "$work/temper.out/blimp/snake-web/main.blimp" | tr -d ' ')
written=$(wc -l < "$here/server.blimp" | tr -d ' ')
echo "app.blimp: $generated lines generated, $written written by hand"
echo
echo "now: blimp $here/app.blimp"
