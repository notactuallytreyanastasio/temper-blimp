# Piet programs

Four Temper libraries written to be compiled with `-b piet`. Each one is
a Piet program because it is an image, and each does something that only
makes sense because it is one.

To build and run one (npiet and curl on the PATH; the date and HTTP come
through `io.sh`, which the build writes beside the image):

```sh
temper build -b piet -w piet-programs/mondrian
cd piet-programs/mondrian/temper.out/piet/mondrian && sh io.sh -q -cs 1 main.ppm
```

The times below are npiet 1.3f on an arm64 Mac. They are long because
be-piet keeps its heap on Piet's stack, and npiet's `roll` moves elements one
at a time, so every heap access costs time in proportion to the heap's size.

## piet-in-piet

[piet-in-piet.temper.md](piet-in-piet/piet-in-piet.temper.md) is a Piet
interpreter: colour blocks found by flood fill, the direction pointer and
codel chooser, the eight attempts before halting, sliding through white,
and the seventeen commands. Compiled, it is a 597 by 5680 image
([piet-in-piet.png](piet-in-piet/piet-in-piet.png)) that runs another Piet
program, embedded in its source as rows of letters.

That one is drawn by [inner.py](piet-in-piet/inner.py), 130 by 3 codels,
shown here eight times larger:

![The inner program](piet-in-piet/inner.png)

npiet runs `inner.ppm` in 34 steps and prints `Piet!`. The interpreter,
running as a Piet program under npiet, takes 36 seconds:

```
A Piet program read a Piet program of 130 by 3 codels, 35 blocks,
ran it for 34 steps, and it said:
Piet!
```

Its first version flood-filled the current block on every step and took
6 minutes 55 seconds; finding every block once, before running, made it 11
times faster. Division by zero follows the specification (the command is
ignored), not npiet (which pushes 99999999). `in(number)` and `in(char)`
panic: the inner program has no stdin of its own.

## mondrian

Piet is named after Piet Mondrian, and three of its normal colours are
his: #FF0000, #FFFF00 and #0000FF. [mondrian.temper.md](mondrian/mondrian.temper.md)
cuts a canvas with black lines and colours a few of the pieces, with ZX81's
random number generator seeded by today's date, so each day has one
painting. A Piet program cannot read a clock; it asks `io.sh` for the date,
which comes from `date -u`. The painting is printed in truecolour ANSI and
takes 90 seconds. For 2026-10-09 (UTC):

![Composition with Red, Yellow and Blue, 2026-10-09](mondrian/2026-10-09.png)

The program that painted it ([mondrian.png](mondrian/mondrian.png)) is a
589 by 3306 image, which is not a Mondrian. Its output matches the same
library run with `-b js` byte for byte.

## fact-check

[fact-check.temper.md](fact-check/fact-check.temper.md) fetches the
Commands section of the esolangs.org article on Piet over HTTPS (through
`io.sh`, which runs curl), prints the command table it finds there, and
then tests one sentence of it. Run under npiet, in 40 seconds:

```
I looked myself up on esolangs.org (HTTP 200, 42 lines of wikitext).
My seventeen commands, by change of hue and of lightness:

No change   N/A           Push          Pop
1 step      Add           Subtract      Multiply
2 steps     Divide        Modulo        Not
3 steps     Greater       Pointer       Switch
4 steps     Duplicate     Roll          Input num
5 steps     Input char    Output num    Output char

It says Divide is "integer division (Python equivalent of //)".
In Python, -7 // 2 is -4.
I just divided -7 by 2 with my own divide command and got -3.
npiet divides the way C does, so every Piet program it runs does too.
Including this one, which is how I know.
```

The `-3` is not a constant: the operands come out of a `ListBuilder`, and
the compiled block that does the division begins with Piet's own `Divide`
(`check__24.nonzero15: Divide ...` in be-piet's debug dump). The article
is live, so the table and the claim are whatever it says when you run it;
if the sentence about Python goes, so does the check.

## life

[life.temper.md](life/life.temper.md) plays Conway's Life on a Piet
program, the one piet-in-piet runs, embedded the same way: three rows of
letters, here over 21 black rows, a board 130 by 24. A codel is alive if
it is not black; a newborn takes the colour two of its three parents
share, or the first's in reading order, so every generation is still a
Piet image. After each one the program runs the board as a Piet program,
with npiet's rules where the specification leaves room (division by zero
pushes 99999999, input with nothing on stdin pushes nothing, out(char)
writes the low byte), for at most 100 steps, and prints the board,
run-length encoded, and what it said. Compiled, it is a 593 by 6885 image
([life.png](life/life.png)).

The first twelve frames here are generations 0-5, 8, 12, 16, 20, 25 and
30, drawn from the program's own output ([output.txt](life/output.txt)):

![Generations of Life on the Piet program](life/generations.png)

Generation 0 says `Piet!`. Generation 1 says `9999...` until it is stopped
at 100 steps. From generation 2 the start codel, (0, 0), is cut off from
everything else, so every later board halts before its first step, while
Life goes on reshaping the rest. Under npiet the 30 generations took
1,265,631,744 steps and 2 h 59 min, slower each generation (38 s for the
2nd, 813 s for the 30th) as the heap grows: be-piet never frees, and each
generation's printed lines stay on it. Every board it printed matches an
independent Python Life with the same rules, and every run matches what
npiet does with that board and `-e 100`.

Getting there took four versions. The first read eight neighbours through
a function and chose with `if`s; in be-piet each call, return and `if` is
a jump round the image's dispatch loop, and a list read is a call, so it
took about 0.15 s a codel. The second slid a 3x3 window and decided with
arithmetic (`(19 - c).min(1)` is 1 for a live codel), but allocated
16,000 cells up front, and allocation on a heap that lives on Piet's stack
costs the square of its size: 588 s before the first line. The third kept
only the two boards, one Int a codel, and took 352 s. The fourth
packs five 6-bit codels to an Int and streams rows a word at a time; with
P23's cheaper Int32 wrap, setup is 33 s.
