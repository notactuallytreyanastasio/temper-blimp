# blimp-highlight

A syntax highlighter for Blimp, written in Temper. Built with `-b blimp`, it is
a Temper program highlighting Blimp while running on the Blimp interpreter.

    $ temper build -b blimp -b js
    $ bin/blimp-hl ../blimp/chunks/lang/examples/bank_account.blimp
    $ bin/blimp-hl --html some.blimp > some.html
    $ bin/blimp-hl --js some.blimp          # the JavaScript translation instead

## What it does

`lex` cuts a source into tokens that lose nothing: joining their text gives the
source back byte for byte, comments and blank lines included. Token kinds are
spelled exactly as Blimp's `token.zig` spells them (`kw_actor`, `atom`,
`async_send`, `upper_identifier`...), plus `space`, `newline` and `comment`,
which Blimp's own lexer throws away and a highlighter cannot.

`styleOf` maps a kind onto what a reader sees: `keyword`, `atom`, `string`,
`number`, `constant`, `comment`, `type`, `builtin`, `hole`, `operator`,
`punctuation`, `name`, `invalid`. A builtin is an identifier the interpreter's
registry defines. The 125 names are read off `reg.register` literals and the
`float_fns` table in `builtins.zig`, not off a grep for `register`, which also
matches actor templates registered by unit tests.

`toHtml` wraps each token in `<span class="bl-STYLE">`. `toAnsi` colours for a
terminal and resets after every coloured token.

## How it is checked

    $ temper test -b js      # and -b py, -b blimp
    Tests passed: 11 of 11

    $ oracle/compare.sh js
    js: 97 files identical, 0 differ

`oracle/compare.sh` rebuilds a token dumper from Blimp's real `lexer.zig` and
`token.zig` on every run, then diffs its output against this lexer's `dump`
for every `.blimp` file git tracks here: 97 files, about 19,500 lines. The
11,000-line generated snake server is gitignored, so it is not among them; run
by hand under `js`, its 62,362 tokens match too.

Pass `blimp` instead of `js` to run the highlighter as Blimp. That takes about
seven minutes and matches 96 of the 97 files. The 97th is the largest,
Temper's own `core.blimp` at 4,322 lines, and it runs out of heap before it
finishes; see below.

## Large files on the Blimp backend

On Blimp the highlighter's memory grows with the square of the file. That is
not this library: be-blimp's `ListBuilder.add` and `StringBuilder.append` copy
their whole contents on every call, and the interpreter frees nothing until the
program exits.

    ListBuilder, 5,000 / 10,000 / 20,000 adds      118 MB / 419 MB / 1.6 GB
    StringBuilder, same counts, 10 bytes each      143 MB / 519 MB / 2.0 GB

Lexing prefixes of `core.blimp` shows the same curve: 250, 500, 1,000 and
2,000 lines take 63, 137, 386 and 1,377 MB. Under the default 2 GB ceiling the
limit is somewhere between 2,000 and 2,500 lines of dense code; that bound is
extrapolated from the curve, not measured. The JavaScript and Python translations
have no such limit.

## Known differences from Blimp

Blimp classifies bytes; Temper walks code points. A non-ASCII character is one
`invalid` token here and one `invalid` token per UTF-8 byte in Blimp. Nothing in
the repository has one outside a string or comment, where both agree.

A generated Blimp program also runs its library's tests on start and writes
`test-results.xml`; `bin/blimp-hl` and the oracle drop that line before running.
