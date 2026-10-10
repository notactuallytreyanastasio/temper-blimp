# Life, in Piet, on a Piet program

Conway's Game of Life, run on a Piet program, by a Piet program: compiled
with `-b piet`, this is a Piet image that holds another Piet program as
data, the one piet-in-piet runs (it prints `Piet!`), and plays Life on it.
After every generation it runs the board as a Piet program, with the same
kind of interpreter piet-in-piet has, and says what it printed.

The board is the inner program's three rows over twenty-one black ones,
130 codels wide. A codel is alive if it is not black. A live codel with
two or three live neighbours lives and keeps its colour; a dead one with
exactly three is born, in the colour two of them share, or if all three
differ, the colour of the first in reading order. So every generation is
still a Piet image, and the edges of the board are dead.

    let rows = [
      "aKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKrK",
      "aaaaaaabbbbbbbbbbceaaaaaaaaaabbbbbbbbbbceeeeefieeeeeeeeeeffffffffffdigjiiiiiiiiiiggggggggggghjjjjjjknjjjjjkkkkkklnnnornnnooomrparK",
      "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKrK",
    ];

    let EXTRA_ROWS = 21;
    let GENERATIONS = 30;
    // npiet -e 100: a generation that has not halted by then is stopped.
    let STEP_LIMIT = 100;

    let WHITE = 18;
    let BLACK = 19;

Truncating, like C and like npiet; b is never 0 here.

    let div(a: Int, b: Int): Int { a / b orelse panic() }
    let mod(a: Int, b: Int): Int { a % b orelse panic() }

    // Direction pointer: 0 right, 1 down, 2 left, 3 up.
    let dx(dp: Int): Int { if (dp == 0) { 1 } else if (dp == 2) { -1 } else { 0 } }
    let dy(dp: Int): Int { if (dp == 1) { 1 } else if (dp == 3) { -1 } else { 0 } }

    let colourOf(letter: Int): Int {
      if (letter == char'K') {
        BLACK
      } else if (letter == char'W') {
        WHITE
      } else {
        letter - char'a'
      }
    }

## Memory, allocated once

be-piet keeps its heap on Piet's stack and never frees it. Every heap
access is a `roll` as deep as the heap, and npiet moves a rolled value
one element at a time, so each read and write costs time in proportion to
the heap's size, and making a list one `add` at a time costs its square.
A first version kept a board as one Int per codel and spent 588 s under
npiet allocating before it printed a line; with only two such boards, 352.

So a board is packed: a codel's colour is 6 bits (0-17, 18 white, 19
black, and up to 51 while a flood fill marks it), five codels to an Int,
a word's first codel in its lowest bits. A row of the board, with a black
border codel at each end, is R words. Two boards are 1,404 Ints.

    let width(s: String): Int {
      var n = 0;
      for (var i = String.begin; i < s.end; i = s.next(i)) { n += 1; }
      n
    }

    let W = width(rows[0]);
    let H = rows.length + EXTRA_ROWS;
    let PW = W + 2;
    let PH = H + 2;
    let R = (PW + 4) / 5;
    // A word of five black codels.
    let BLACKS = 19 + 19 * 64 + 19 * 4096 + 19 * 262144 + 19 * 16777216;

    let fresh(n: Int, v: Int): ListBuilder<Int> {
      let l = new ListBuilder<Int>();
      for (var i = 0; i < n; i += 1) { l.add(v); }
      l
    }

    var board = fresh(R * PH, BLACKS);
    var scratch = fresh(R * PH, BLACKS);
    // 64 to the power of a codel's place in its word.
    let PLACE = [1, 64, 4096, 262144, 16777216];
    // The current block's exit codels, two per (dp, cc), and its size.
    let exits = fresh(16, 0);
    // A flood fill's queue: as long as the largest block yet.
    let qx = new ListBuilder<Int>();
    let qy = new ListBuilder<Int>();

    // A codel of the board, in padded coordinates. (Dividing by 64, a
    // constant, cannot fail; dividing by PLACE[k] could, as far as the
    // compiler knows.)
    let cell(px: Int, py: Int): Int {
      var w = board[py * R + px / 5];
      for (var k = px % 5; k > 0; k -= 1) { w = w / 64; }
      w % 64
    }

    let setCell(px: Int, py: Int, c: Int): Void {
      board.set(py * R + px / 5, board[py * R + px / 5] + (c - cell(px, py)) * PLACE[px % 5]);
    }

    let seed(): Void {
      for (var y = 0; y < rows.length; y += 1) {
        let line = rows[y];
        var x = 0;
        for (var i = String.begin; i < line.end; i = line.next(i)) {
          setCell(x + 1, y + 1, colourOf(line[i]));
          x += 1;
        }
      }
    }

    let at(x: Int, y: Int): Int {
      if (x < 0 || y < 0 || x >= W || y >= H) { BLACK } else { cell(x + 1, y + 1) }
    }

## Life

Why this looks the way it does: in be-piet every jump between basic blocks
(a call, a return, an `if`) goes once round the image's dispatch loop,
hundreds of npiet steps on a program this size, and a list read is a call.
Measured on 1,000 iterations, a list read and write cost about 2,500 steps
where an iteration of plain arithmetic costs 71. The first version of this
step read eight neighbours through a function and chose with `if`s, and
took about 0.15 s a codel under npiet.

So the step streams three rows of the board a word at a time, peeling a
codel off each with `% 64` and `/ 64`, which compile to Piet's own mod and
divide in one block; it slides a 3x3 window along them, and decides with
arithmetic: `(19 - c).min(1)` is 1 for a live codel and 0 for black,
`1 - (d * d).min(1)` is 1 when d is 0. It works only on the rows that can
change: those within one of a live codel this generation or the last (the
board it writes into still holds the last one).

The rule for one codel, from its window: a, b, c are the columns left of,
at and right of it, 0 above, 1 level, 2 below. Its neighbours in reading
order are a0 b0 c0 a1 c1 a2 b2 c2.

    let rule(a0: Int, a1: Int, a2: Int, b0: Int, b1: Int, b2: Int, c0: Int, c1: Int, c2: Int): Int {
      let la0 = (19 - a0).min(1);
      let lb0 = (19 - b0).min(1);
      let lc0 = (19 - c0).min(1);
      let la1 = (19 - a1).min(1);
      let lc1 = (19 - c1).min(1);
      let la2 = (19 - a2).min(1);
      let lb2 = (19 - b2).min(1);
      let lc2 = (19 - c2).min(1);
      let n = la0 + lb0 + lc0 + la1 + lc1 + la2 + lb2 + lc2;
      // The first three live neighbours' colours, f1, f2, f3: each adds
      // its colour to the slot its rank, k, names.
      var k = 0;
      var f1 = 0;
      var f2 = 0;
      var f3 = 0;
      f1 += la0 * a0; k += la0;
      f1 += lb0 * (1 - k.min(1)) * b0; f2 += lb0 * k * b0; k += lb0;
      f1 += lc0 * (1 - k.min(1)) * c0; f2 += lc0 * (1 - ((k - 1) * (k - 1)).min(1)) * c0; f3 += lc0 * (1 - ((k - 2) * (k - 2)).min(1)) * c0; k += lc0;
      f1 += la1 * (1 - k.min(1)) * a1; f2 += la1 * (1 - ((k - 1) * (k - 1)).min(1)) * a1; f3 += la1 * (1 - ((k - 2) * (k - 2)).min(1)) * a1; k += la1;
      f1 += lc1 * (1 - k.min(1)) * c1; f2 += lc1 * (1 - ((k - 1) * (k - 1)).min(1)) * c1; f3 += lc1 * (1 - ((k - 2) * (k - 2)).min(1)) * c1; k += lc1;
      f1 += la2 * (1 - k.min(1)) * a2; f2 += la2 * (1 - ((k - 1) * (k - 1)).min(1)) * a2; f3 += la2 * (1 - ((k - 2) * (k - 2)).min(1)) * a2; k += la2;
      f1 += lb2 * (1 - k.min(1)) * b2; f2 += lb2 * (1 - ((k - 1) * (k - 1)).min(1)) * b2; f3 += lb2 * (1 - ((k - 2) * (k - 2)).min(1)) * b2; k += lb2;
      f3 += lc2 * (1 - ((k - 2) * (k - 2)).min(1)) * c2;
      let lhere = (19 - b1).min(1);
      let is2 = 1 - ((n - 2) * (n - 2)).min(1);
      let is3 = 1 - ((n - 3) * (n - 3)).min(1);
      let survive = lhere * (is2 + is3);
      let born = (1 - lhere) * is3;
      // Born: the colour two of the three share, else the first's.
      let same12 = 1 - ((f1 - f2) * (f1 - f2)).min(1);
      let same13 = 1 - ((f1 - f3) * (f1 - f3)).min(1);
      let same23 = 1 - ((f2 - f3) * (f2 - f3)).min(1);
      let child = f1 + same23 * (1 - same12) * (1 - same13) * (f2 - f1);
      survive * b1 + born * child + (1 - survive - born) * BLACK
    }

    var lo = 0;
    var hi = 2;
    var lastLo = 0;
    var lastHi = 2;
    var live = 0;

    let generation(): Void {
      let from = lo.min(lastLo) - 1;
      let to = hi.max(lastHi) + 1;
      var newLo = H;
      var newHi = -1;
      var count = 0;
      for (var y = from.max(0); y <= to.min(H - 1); y += 1) {
        let up = y * R;
        let mid = up + R;
        let down = mid + R;
        // The window, entering from the left: a and b are the two columns
        // before the first codel, outside the board, so black.
        var a0 = BLACK;
        var a1 = BLACK;
        var a2 = BLACK;
        var b0 = BLACK;
        var b1 = BLACK;
        var b2 = BLACK;
        // The word being written, and how many codels it holds.
        var out = 0;
        for (var j = 0; j <= R; j += 1) {
          // Past the last word, a word of black closes the row.
          var wu = BLACKS;
          var wm = BLACKS;
          var wd = BLACKS;
          if (j < R) {
            wu = board[up + j];
            wm = board[mid + j];
            wd = board[down + j];
          }
          var place = 1;
          for (var t = 0; t < 5; t += 1) {
            let c0 = wu % 64;
            let c1 = wm % 64;
            let c2 = wd % 64;
            wu = wu / 64;
            wm = wm / 64;
            wd = wd / 64;
            // The window's centre, b1, is the codel one left of this one.
            let px = j * 5 + t - 1;
            // A centre outside 1..W is border, and stays black.
            let keep = px.max(0).min(1) * (W + 1 - px).max(0).min(1);
            let colour = keep * rule(a0, a1, a2, b0, b1, b2, c0, c1, c2) + (1 - keep) * BLACK;
            let alive = (19 - colour).min(1);
            count += alive;
            newLo = newLo.min(y + (1 - alive) * H);
            newHi = newHi.max(y - (1 - alive) * H);
            // px - 1 is the last place of the word before: write that word.
            if (t == 0) {
              if (j > 0) { scratch.set(mid + j - 1, out + colour * 16777216); }
              out = 0;
            } else {
              out += colour * place;
              place *= 64;
            }
            a0 = b0; a1 = b1; a2 = b2;
            b0 = c0; b1 = c1; b2 = c2;
          }
        }
      }
      let old = board;
      board = scratch;
      scratch = old;
      lastLo = lo;
      lastHi = hi;
      lo = newLo;
      hi = newHi;
      live = count;
    }

## Running a generation

The block holding (x, y), found by a flood fill that marks a codel it has
reached by adding 32 to its colour on the board, and takes the marks off
when it is done. Fills exits and returns the block's size. A board does
not change while it runs, so the block found from a codel is remembered
for the rest of the generation: `known` maps the codel to where its exits
and size are in `cache`.

    let known = new MapBuilder<Int, Int>();
    let cache = new ListBuilder<Int>();

    let queue(i: Int, x: Int, y: Int): Void {
      if (i < qx.length) {
        qx.set(i, x);
        qy.set(i, y);
      } else {
        qx.add(x);
        qy.add(y);
      }
    }

    let mark(x: Int, y: Int, by: Int): Void {
      let i = (y + 1) * R + (x + 1) / 5;
      board.set(i, board[i] + by * PLACE[(x + 1) % 5]);
    }

    let block(x0: Int, y0: Int): Int {
      let key = y0 * W + x0;
      let hit = known.getOr(key, -1);
      if (hit >= 0) {
        for (var i = 0; i < 16; i += 1) { exits.set(i, cache[hit + i]); }
        return cache[hit + 16];
      }
      let colour = at(x0, y0);
      queue(0, x0, y0);
      mark(x0, y0, 32);
      var size = 1;
      var head = 0;
      while (head < size) {
        let x = qx[head];
        let y = qy[head];
        head += 1;
        for (var d = 0; d < 4; d += 1) {
          let nx = x + dx(d);
          let ny = y + dy(d);
          if (at(nx, ny) == colour) {
            mark(nx, ny, 32);
            queue(size, nx, ny);
            size += 1;
          }
        }
      }
      for (var i = 0; i < size; i += 1) { mark(qx[i], qy[i], -32); }
      for (var dp = 0; dp < 4; dp += 1) {
        for (var cc = 0; cc < 2; cc += 1) {
          // Furthest in the dp direction, then furthest in the direction
          // cc turns dp to: left is counterclockwise, right clockwise.
          let turn = if (cc == 0) { 3 } else { 1 };
          let side = mod(dp + turn, 4);
          var best = 0;
          for (var i = 1; i < size; i += 1) {
            let far = qx[i] * dx(dp) + qy[i] * dy(dp);
            let bestFar = qx[best] * dx(dp) + qy[best] * dy(dp);
            let along = qx[i] * dx(side) + qy[i] * dy(side);
            let bestAlong = qx[best] * dx(side) + qy[best] * dy(side);
            if (far > bestFar || far == bestFar && along > bestAlong) { best = i; }
          }
          exits.set((dp * 2 + cc) * 2, qx[best]);
          exits.set((dp * 2 + cc) * 2 + 1, qy[best]);
        }
      }
      known.set(key, cache.length);
      for (var i = 0; i < 16; i += 1) { cache.add(exits[i]); }
      cache.add(size);
      size
    }

What npiet does, where the specification leaves room or the program could
not otherwise go on (each checked against npiet 1.3f): divide and mod by
zero push 99999999; in(number) and in(char) with nothing on stdin push
nothing; out(char) writes the low byte.

    class Machine {
      public stack: ListBuilder<Int> = new ListBuilder<Int>();
      public said: ListBuilder<Int> = new ListBuilder<Int>();
      public var x: Int = 0;
      public var y: Int = 0;
      public var dp: Int = 0;
      public var cc: Int = 0;
      public var steps: Int = 0;

      private pop(): Int { stack.removeLast() }
      private push(n: Int): Void { stack.add(n); }
      private has(n: Int): Boolean { stack.length >= n }

      public command(hue: Int, light: Int, size: Int): Void {
        when (hue * 3 + light) {
          1 -> push(size);
          2 -> if (has(1)) { pop(); }
          3 -> if (has(2)) { let b = pop(); push(pop() + b); }
          4 -> if (has(2)) { let b = pop(); push(pop() - b); }
          5 -> if (has(2)) { let b = pop(); push(pop() * b); }
          6 -> if (has(2)) { let b = pop(); let a = pop(); push(if (b == 0) { 99999999 } else { div(a, b) }); }
          7 -> if (has(2)) { let b = pop(); let a = pop(); push(if (b == 0) { 99999999 } else { mod(a, b) }); }
          8 -> if (has(1)) { push(if (pop() == 0) { 1 } else { 0 }); }
          9 -> if (has(2)) { let b = pop(); push(if (pop() > b) { 1 } else { 0 }); }
          10 -> if (has(1)) { dp = mod(mod(dp + pop(), 4) + 4, 4); }
          11 -> if (has(1)) { if (mod(pop(), 2) != 0) { cc = 1 - cc; } }
          12 -> if (has(1)) { let a = pop(); push(a); push(a); }
          13 -> if (has(2)) { roll(); }
          16 -> if (has(1)) { let n = pop(); let s = n.toString(); for (var i = String.begin; i < s.end; i = s.next(i)) { said.add(s[i]); } }
          17 -> if (has(1)) { said.add(mod(mod(pop(), 256) + 256, 256)); }
          // 14 and 15, in(number) and in(char): nothing on stdin, nothing pushed.
        }
      }

      private roll(): Void {
        let turns = pop();
        let depth = pop();
        if (depth <= 0 || depth > stack.length) {
          push(depth);
          push(turns);
          return;
        }
        let n = mod(mod(turns, depth) + depth, depth);
        for (var t = 0; t < n; t += 1) {
          let top = pop();
          stack.add(top, stack.length - depth + 1);
        }
      }

      // One step, as npiet takes it. False once the program has halted.
      public step(): Boolean {
        let size = block(x, y);
        let colour = at(x, y);
        for (var attempt = 0; attempt < 8; attempt += 1) {
          let i = (dp * 2 + cc) * 2;
          let nx = exits[i] + dx(dp);
          let ny = exits[i + 1] + dy(dp);
          let next = at(nx, ny);
          if (next == WHITE) { return slide(nx, ny); }
          if (next != BLACK) {
            let dh = mod(div(next, 3) - div(colour, 3) + 6, 6);
            let dl = mod(mod(next, 3) - mod(colour, 3) + 3, 3);
            x = nx;
            y = ny;
            steps += 1;
            command(dh, dl, size);
            return true;
          }
          if (mod(attempt, 2) == 0) { cc = 1 - cc; } else { dp = mod(dp + 1, 4); }
        }
        false
      }

      // White is never born here (nothing white is alive to pass it on),
      // so a board has none; a slide is TODO, not a guess.
      private slide(x0: Int, y0: Int): Boolean {
        panic()
      }
    }

    // Runs the board; npiet starts at (0, 0), and on black ends at once.
    let run(machine: Machine): Boolean {
      known.clear();
      cache.clear();
      if (at(0, 0) == BLACK) { return true; }
      while (machine.steps < STEP_LIMIT) {
        if (!machine.step()) { return true; }
      }
      false
    }

## What it prints

A generation is three lines. The board, run-length encoded in reading
order, a count before each letter (`1a129K...`); the run; and what the
board printed, as the codes npiet wrote.

    let letter(c: Int): String {
      String.fromCodePoint(if (c == BLACK) { char'K' } else if (c == WHITE) { char'W' } else { char'a' + c }) orelse panic()
    }

    // Rows outside lo..hi are black; they are counted, not read. Rows
    // inside are read a word at a time, five codels to a read.
    let encode(): String {
      let out = new StringBuilder();
      var run = 0;
      var last = BLACK;
      for (var y = 0; y < H; y += 1) {
        if (y < lo || y > hi) {
          if (last != BLACK) {
            out.append(run.toString());
            out.append(letter(last));
            run = 0;
            last = BLACK;
          }
          run += W;
        } else {
          let row = (y + 1) * R;
          for (var j = 0; j < R; j += 1) {
            var w = board[row + j];
            for (var t = 0; t < 5; t += 1) {
              let c = w % 64;
              w = w / 64;
              let px = j * 5 + t;
              if (px >= 1 && px <= W) {
                if (c != last) {
                  if (run > 0) {
                    out.append(run.toString());
                    out.append(letter(last));
                  }
                  run = 0;
                  last = c;
                }
                run += 1;
              }
            }
          }
        }
      }
      out.append(run.toString());
      out.append(letter(last));
      out.toString()
    }

    let codes(said: ListBuilder<Int>): String {
      let out = new StringBuilder();
      for (var i = 0; i < said.length; i += 1) {
        if (i > 0) { out.append(" "); }
        out.append(said[i].toString());
      }
      out.toString()
    }

    let text(said: ListBuilder<Int>): String {
      let out = new StringBuilder();
      for (var i = 0; i < said.length; i += 1) {
        let c = said[i];
        if (c == 10) {
          out.append("\\n");
        } else if (c >= 32 && c < 127) {
          out.append(String.fromCodePoint(c) orelse panic());
        } else {
          out.append("?");
        }
      }
      out.toString()
    }

    // In a function: be-piet's top-level code reaches its variables more
    // slowly than a function reaches its locals (555 npiet steps an
    // iteration against 71, for the same loop).
    let main(): Void {
      seed();
      live = 0;
      for (var y = 0; y < H; y += 1) {
        for (var x = 0; x < W; x += 1) { live += (19 - cell(x + 1, y + 1)).min(1); }
      }
      console.log("life ${W} ${H} ${GENERATIONS} ${STEP_LIMIT}");
      for (var g = 0; g <= GENERATIONS; g += 1) {
        let machine = new Machine();
        let halted = run(machine);
        let how = if (halted) { "halted" } else { "stopped" };
        console.log("board ${g} ${live} ${encode()}");
        console.log("run ${g} ${machine.steps} ${how} said \"${text(machine.said)}\"");
        console.log("codes ${g} ${codes(machine.said)}");
        generation();
      }
    }

    main();
