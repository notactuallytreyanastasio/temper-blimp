# Piet in Piet

A Piet interpreter, written in Temper. Built with `-b piet`, it is itself a
Piet program: an image that reads another image, codel by codel, and does
what that one says.

The program it runs is drawn by [inner.py](inner.py): one row of colour
blocks between two black rows, ending in a block three codels tall that
traps the interpreter. npiet runs `inner.ppm` in 34 steps and prints
`Piet!`. Each letter below is one codel: `a` to `r` are the eighteen
colours, hue by hue and light, normal, dark within each; `K` is black and
`W` white.

    let rows = [
      "aKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKrK",
      "aaaaaaabbbbbbbbbbceaaaaaaaaaabbbbbbbbbbceeeeefieeeeeeeeeeffffffffffdigjiiiiiiiiiiggggggggggghjjjjjjknjjjjjkkkkkklnnnornnnooomrparK",
      "KKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKKrK",
    ];

    let WHITE = 18;
    let BLACK = 19;

## The image

    class Image(
      public width: Int,
      public height: Int,
      public cells: List<Int>,
    ) {
      public at(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) {
          BLACK
        } else {
          cells[y * width + x]
        }
      }
    }

    let colourOf(letter: Int): Int {
      if (letter == char'K') {
        BLACK
      } else if (letter == char'W') {
        WHITE
      } else {
        letter - char'a'
      }
    }

    let read(lines: List<String>): Image {
      let cells = new ListBuilder<Int>();
      var width = 0;
      for (var y = 0; y < lines.length; y += 1) {
        let line = lines[y];
        var x = 0;
        for (var i = String.begin; i < line.end; i = line.next(i)) {
          cells.add(colourOf(line[i]));
          x += 1;
        }
        width = x;
      }
      new Image(width, lines.length, cells.toList())
    }

## Blocks

A colour block is every codel of one colour that touches another of them.
Leaving a block, the direction pointer picks the edge furthest that way,
and the codel chooser picks one end of that edge.

    class Block(
      public size: Int,
      // For each of the 8 (dp, cc) pairs, the codel the block is left from.
      public exitX: List<Int>,
      public exitY: List<Int>,
    ) {}

    // Truncating, like C and like npiet; b is never 0 here.
    let div(a: Int, b: Int): Int { a / b orelse panic() }
    let mod(a: Int, b: Int): Int { a % b orelse panic() }

    // Direction pointer: 0 right, 1 down, 2 left, 3 up.
    let dx(dp: Int): Int { if (dp == 0) { 1 } else if (dp == 2) { -1 } else { 0 } }
    let dy(dp: Int): Int { if (dp == 1) { 1 } else if (dp == 3) { -1 } else { 0 } }

    // Every codel's block, found once: a flood fill from each codel no
    // block has claimed yet. Black and white codels belong to no block.
    class Blocks(public image: Image) {
      public label: ListBuilder<Int> = new ListBuilder<Int>();
      public all: ListBuilder<Block> = new ListBuilder<Block>();

      public at(x: Int, y: Int): Block { all[label[y * image.width + x]] }

      public find(): Void {
        let w = image.width;
        for (var i = 0; i < w * image.height; i += 1) { label.add(-1); }
        for (var y0 = 0; y0 < image.height; y0 += 1) {
          for (var x0 = 0; x0 < w; x0 += 1) {
            let colour = image.at(x0, y0);
            if (colour < WHITE && label[y0 * w + x0] < 0) {
              let id = all.length;
              let xs = new ListBuilder<Int>();
              let ys = new ListBuilder<Int>();
              xs.add(x0);
              ys.add(y0);
              label.set(y0 * w + x0, id);
              var next = 0;
              while (next < xs.length) {
                let x = xs[next];
                let y = ys[next];
                next += 1;
                for (var d = 0; d < 4; d += 1) {
                  let nx = x + dx(d);
                  let ny = y + dy(d);
                  if (image.at(nx, ny) == colour && label[ny * w + nx] < 0) {
                    label.set(ny * w + nx, id);
                    xs.add(nx);
                    ys.add(ny);
                  }
                }
              }
              all.add(edges(xs, ys));
            }
          }
        }
      }
    }

    let edges(xs: ListBuilder<Int>, ys: ListBuilder<Int>): Block {
      let exitX = new ListBuilder<Int>();
      let exitY = new ListBuilder<Int>();
      for (var dp = 0; dp < 4; dp += 1) {
        for (var cc = 0; cc < 2; cc += 1) {
          // How far a codel is in the dp direction, then in the direction
          // cc turns dp to: left is counterclockwise, right clockwise.
          let turn = if (cc == 0) { 3 } else { 1 };
          let side = mod(dp + turn, 4);
          var best = 0;
          for (var i = 1; i < xs.length; i += 1) {
            let far = xs[i] * dx(dp) + ys[i] * dy(dp);
            let bestFar = xs[best] * dx(dp) + ys[best] * dy(dp);
            let along = xs[i] * dx(side) + ys[i] * dy(side);
            let bestAlong = xs[best] * dx(side) + ys[best] * dy(side);
            if (far > bestFar || far == bestFar && along > bestAlong) {
              best = i;
            }
          }
          exitX.add(xs[best]);
          exitY.add(ys[best]);
        }
      }
      new Block(xs.length, exitX.toList(), exitY.toList())
    }

## The machine

    class Machine(
      public image: Image,
      public blocks: Blocks,
    ) {
      public stack: ListBuilder<Int> = new ListBuilder<Int>();
      public out: StringBuilder = new StringBuilder();
      public var x: Int = 0;
      public var y: Int = 0;
      public var dp: Int = 0;
      public var cc: Int = 0;
      public var steps: Int = 0;

      private pop(): Int { stack.removeLast() }
      private push(n: Int): Void { stack.add(n); }

      // Commands that need more than the stack has are ignored, as the
      // specification says.
      private has(n: Int): Boolean { stack.length >= n }

      public command(hue: Int, light: Int, size: Int): Void {
        when (hue * 3 + light) {
          1 -> push(size);
          2 -> if (has(1)) { pop(); }
          3 -> if (has(2)) { let b = pop(); push(pop() + b); }
          4 -> if (has(2)) { let b = pop(); push(pop() - b); }
          5 -> if (has(2)) { let b = pop(); push(pop() * b); }
          // Division by zero is ignored here, as the specification says;
          // npiet pushes 99999999 instead.
          6 -> if (has(2) && stack[stack.length - 1] != 0) { let b = pop(); push(div(pop(), b)); }
          7 -> if (has(2) && stack[stack.length - 1] != 0) { let b = pop(); push(mod(pop(), b)); }
          8 -> if (has(1)) { push(if (pop() == 0) { 1 } else { 0 }); }
          9 -> if (has(2)) { let b = pop(); push(if (pop() > b) { 1 } else { 0 }); }
          10 -> if (has(1)) { dp = mod(mod(dp + pop(), 4) + 4, 4); }
          11 -> if (has(1)) { if (mod(pop(), 2) != 0) { cc = 1 - cc; } }
          12 -> if (has(1)) { let a = pop(); push(a); push(a); }
          13 -> if (has(2)) { roll(); }
          16 -> if (has(1)) { out.append(pop().toString()); }
          17 -> if (has(1)) { out.append(String.fromCodePoint(pop()) orelse panic()); }
          // in(number) and in(char): the inner program has no stdin.
          else -> panic();
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
          // One turn buries the top value depth - 1 places down.
          let top = pop();
          stack.add(top, stack.length - depth + 1);
        }
      }

      // One step: leave the current block, or find that every way out is
      // shut. Returns false once the program has halted.
      public step(): Boolean {
        let block = blocks.at(x, y);
        let colour = image.at(x, y);
        for (var attempt = 0; attempt < 8; attempt += 1) {
          let i = dp * 2 + cc;
          let nx = block.exitX[i] + dx(dp);
          let ny = block.exitY[i] + dy(dp);
          let next = image.at(nx, ny);
          if (next == WHITE) {
            return slide(nx, ny);
          }
          if (next != BLACK) {
            let dh = mod(div(next, 3) - div(colour, 3) + 6, 6);
            let dl = mod(mod(next, 3) - mod(colour, 3) + 3, 3);
            x = nx;
            y = ny;
            steps += 1;
            command(dh, dl, block.size);
            return true;
          }
          if (mod(attempt, 2) == 0) {
            cc = 1 - cc;
          } else {
            dp = mod(dp + 1, 4);
          }
        }
        false
      }

      // Through white, in a straight line, executing nothing. Blocked, it
      // toggles cc and turns dp; back where it has been, it halts.
      private slide(x0: Int, y0: Int): Boolean {
        var wx = x0;
        var wy = y0;
        let seen = new ListBuilder<Int>();
        while (true) {
          let nx = wx + dx(dp);
          let ny = wy + dy(dp);
          let next = image.at(nx, ny);
          if (next == WHITE) {
            wx = nx;
            wy = ny;
          } else if (next == BLACK) {
            let here = (wy * image.width + wx) * 4 + dp;
            for (var i = 0; i < seen.length; i += 1) {
              if (seen[i] == here) { return false; }
            }
            seen.add(here);
            cc = 1 - cc;
            dp = mod(dp + 1, 4);
          } else {
            x = nx;
            y = ny;
            steps += 1;
            return true;
          }
        }
        false
      }
    }

## Running it

    let image = read(rows);
    let blocks = new Blocks(image);
    blocks.find();
    let machine = new Machine(image, blocks);
    while (machine.step()) {}
    console.log("A Piet program read a Piet program of ${image.width} by ${image.height} codels, ${blocks.all.length} blocks,");
    console.log("ran it for ${machine.steps} steps, and it said:");
    // What it said ends in a newline, which console.log would double.
    let said = machine.out.toString();
    let last = said.prev(said.end);
    console.log(if (said[last] == char'\n') { said.slice(String.begin, last) } else { said });
