# Composition with Red, Yellow and Blue

Piet is named after Piet Mondrian, and its six normal colours include the
three he painted with. This is a Piet program that paints a Mondrian: a
canvas cut by black lines into rectangles, a few of them red, yellow or
blue, the rest white. The cuts come from a small random number generator
seeded with today's date, which a Piet program can only learn by asking
for it (see Io.kt in be-piet), so each day gets one painting and keeps it.

    let { Date } = import("std/temporal");

    let today = Date.today();

ZX81's generator: small enough that nothing overflows 32 bits.

    var seed = (today.year * 372 + today.month * 31 + today.day) % 65537 orelse 0;
    let random(n: Int): Int {
      seed = (seed * 75 + 74) % 65537 orelse 0;
      seed % n orelse 0
    }

The canvas is cells of one colour each: 0 white, 1 red, 2 yellow, 3 blue,
4 black.

    let WIDTH = 44;
    let HEIGHT = 22;
    let canvas = new ListBuilder<Int>();
    for (var i = 0; i < WIDTH * HEIGHT; i += 1) { canvas.add(0); }

    let fill(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int): Void {
      for (var y = y0; y < y1; y += 1) {
        for (var x = x0; x < x1; x += 1) {
          canvas.set(y * WIDTH + x, colour);
        }
      }
    }

Cut a rectangle with a black line across its longer side, somewhere in its
middle, and cut the two halves again, until a piece is too small or the
generator decides to stop. Mondrian's pieces are mostly white.

    let paint(x0: Int, y0: Int, x1: Int, y1: Int, depth: Int): Void {
      let w = x1 - x0;
      let h = y1 - y0;
      let stop = depth >= 6 || depth >= 3 && random(3) == 0;
      if (stop || w < 10 && h < 5) {
        let roll = random(10);
        let colour = if (roll == 0) { 1 } else if (roll == 1) { 2 } else if (roll == 2) { 3 } else { 0 };
        fill(x0, y0, x1, y1, colour);
      } else if (w * 2 > h * 3 && w >= 10 || h < 5) {
        let at = x0 + 4 + random(w - 8);
        fill(at, y0, at + 1, y1, 4);
        paint(x0, y0, at, y1, depth + 1);
        paint(at + 1, y0, x1, y1, depth + 1);
      } else {
        let at = y0 + 2 + random(h - 4);
        fill(x0, at, x1, at + 1, 4);
        paint(x0, y0, x1, at, depth + 1);
        paint(x0, at + 1, x1, y1, depth + 1);
      }
    }

    paint(0, 0, WIDTH, HEIGHT, 0);

Each cell is two spaces on a truecolour background: Mondrian's red, yellow
and blue are Piet's red, yellow and blue, #FF0000, #FFFF00 and #0000FF.

    let esc = String.fromCodePoint(27) orelse panic();
    let background = [
      "255;255;255", "255;0;0", "255;255;0", "0;0;255", "0;0;0",
    ];
    console.log("Composition with Red, Yellow and Blue, ${today.toString()}");
    console.log("after Piet Mondrian (1872-1944), by a Piet program");
    console.log("");
    for (var y = 0; y < HEIGHT; y += 1) {
      let line = new StringBuilder();
      var last = -1;
      for (var x = 0; x < WIDTH; x += 1) {
        let colour = canvas[y * WIDTH + x];
        if (colour != last) {
          line.append("${esc}[48;2;${background[colour]}m");
          last = colour;
        }
        line.append("  ");
      }
      line.append("${esc}[0m");
      console.log(line.toString());
    }
