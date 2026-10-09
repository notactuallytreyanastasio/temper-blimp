# A Piet program looks itself up

This program fetches the Commands section of the esolangs.org article on
Piet, over HTTP, from inside a Piet image: it writes the request on stdout
and io.sh, beside the image, answers on its stdin (see Io.kt in be-piet).
It prints the command table it finds, and then checks one claim in the
article against itself.

    let { NetRequest, NetResponse } = import("std/net");

    let url = "https://esolangs.org/w/index.php?title=Piet&action=raw&section=11";

## Reading wikitext

    let isSpace(c: Int): Boolean { c == char' ' || c == char'\t' }

    let trim(s: String): String {
      var b = String.begin;
      while (b < s.end && isSpace(s[b])) { b = s.next(b); }
      var e = s.end;
      while (e > b && isSpace(s[s.prev(e)])) { e = s.prev(e); }
      s.slice(b, e)
    }

    let startsWith(s: String, prefix: String): Boolean {
      var i = String.begin;
      var j = String.begin;
      while (j < prefix.end) {
        if (i >= s.end || s[i] != prefix[j]) { return false; }
        i = s.next(i);
        j = prefix.next(j);
      }
      true
    }

    let contains(s: String, part: String): Boolean { s.split(part).length > 1 }

    // Everything after the first `n` characters.
    let drop(s: String, n: Int): String {
      var i = String.begin;
      for (var k = 0; k < n && i < s.end; k += 1) { i = s.next(i); }
      s.slice(i, s.end)
    }

    let pad(s: String, width: Int): String {
      let out = new StringBuilder();
      out.append(s);
      var n = 0;
      for (var i = String.begin; i < s.end; i = s.next(i)) { n += 1; }
      for (var k = n; k < width; k += 1) { out.append(" "); }
      out.toString()
    }

## The table

A row of the table is a header line, `! 2 steps`, then a line of cells,
`| Divide || Modulo || Not`.

    let table(lines: List<String>): Void {
      var hue = "";
      for (var i = 0; i < lines.length; i += 1) {
        let line = lines[i];
        if (startsWith(line, "! ") && !contains(line, "|")) {
          hue = trim(drop(line, 2));
        } else if (startsWith(line, "| ") && hue != "") {
          let cells = drop(line, 2).split("||");
          let row = new StringBuilder();
          row.append("  ");
          row.append(pad(hue, 12));
          for (var c = 0; c < cells.length; c += 1) {
            row.append(pad(trim(cells[c]), 14));
          }
          console.log(trim(row.toString()));
        }
      }
    }

## The claim

The numbers come out of a list so that the compiler cannot divide them
before the program runs: this `/` is Piet's own divide command.

    let operands = new ListBuilder<Int>();
    operands.add(-7);
    operands.add(2);

    let check(lines: List<String>): Void {
      for (var i = 0; i < lines.length; i += 1) {
        if (startsWith(lines[i], "* '''Divide:'''") && contains(lines[i], "Python equivalent of //")) {
          let a = operands[0];
          let b = operands[1];
          console.log("");
          console.log("It says Divide is \"integer division (Python equivalent of //)\".");
          console.log("In Python, ${a} // ${b} is -4.");
          console.log("I just divided ${a} by ${b} with my own divide command and got ${a / b orelse panic()}.");
          console.log("npiet divides the way C does, so every Piet program it runs does too.");
          console.log("Including this one, which is how I know.");
        }
      }
    }

## Asking

    async { (): GeneratorResult<Empty> extends GeneratorFn =>
      do {
        let response: NetResponse = await (new NetRequest(url).send());
        let text = (await response.bodyContent) ?? "";
        let lines = text.split("\n");
        console.log("I looked myself up on esolangs.org (HTTP ${response.status}, ${lines.length} lines of wikitext).");
        console.log("My seventeen commands, by change of hue and of lightness:");
        console.log("");
        table(lines);
        check(lines);
      } orelse console.log("I tried to look myself up, and the internet said no.");
    }
