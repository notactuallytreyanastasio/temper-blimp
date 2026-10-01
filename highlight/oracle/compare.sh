#!/bin/sh
# Diffs the highlighter's token stream against Blimp's own lexer, file by file.
#
#   oracle/compare.sh [blimp|js] [file.blimp ...]
#
# With no files it takes every .blimp file git tracks in this repository. The
# oracle is rebuilt from blimp/chunks/lang/src/{lexer,token}.zig on every run,
# so it can never drift from the lexer it stands in for.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
lib=$(cd "$here/.." && pwd)
root=$(cd "$lib/.." && pwd)
backend=${1:-blimp}; [ $# -gt 0 ] && shift

work=$(mktemp -d)
cp "$root/blimp/chunks/lang/src/lexer.zig" "$root/blimp/chunks/lang/src/token.zig" "$here/dump.zig" "$work/"
( cd "$work" && zig build-exe dump.zig -O ReleaseSafe -femit-bin="$work/oracle" )

if [ $# -eq 0 ]; then
  set -- $(cd "$root" && git ls-files '*.blimp' | sed "s|^|$root/|")
fi

# The generated program runs the library's own tests before anything else;
# the runner drops that line and asks for one file's tokens instead.
main="$lib/temper.out/blimp/blimp-highlight/main.blimp"
[ "$backend" = blimp ] && grep -v '^temper_run_tests(' "$main" > "$work/lib.blimp"

pass=0; fail=0
for f in "$@"; do
  "$work/oracle" "$f" > "$work/want"
  case "$backend" in
    blimp)
      { cat "$work/lib.blimp"; printf 'puts(dump(read_file("%s")))\n' "$f"; } > "$work/run.blimp"
      blimp "$work/run.blimp" | sed '$d' > "$work/got" ;;
    js) node "$here/dump.mjs" "$f" > "$work/got" ;;
  esac
  if cmp -s "$work/want" "$work/got"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
    echo "DIFF $f ($(wc -l < "$work/want" | tr -d ' ') tokens)"
    diff "$work/want" "$work/got" | head -6
  fi
done
echo "$backend: $pass files identical, $fail differ"
[ "$fail" -eq 0 ]
