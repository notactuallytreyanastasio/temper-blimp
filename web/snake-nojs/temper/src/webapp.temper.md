# Snake as a web page, with no script in it

Everything a browser needs to play this arrives as HTML. There is no
JavaScript on the page, so there is nothing for a client to run: the clock is
a `<meta http-equiv="refresh">` and the controls are links.

That makes the whole application a function from a request path to a page, and
this file is that function. The Blimp process around it only moves bytes: it
accepts a socket, hands the path here, and writes back what comes out.

    let {
      Direction, Up, Down, Left, Right, Playing, SnakeGame,
      newGame, changeDirection, tick, render,
    } = import("snake");

## The state

    export class WebApp(
      public game: SnakeGame,
      public dir: Direction,
      public seed: Int,
      public ticks: Int,
    ) {

Routing. A path is one of five things and anything else is the board as it
stands, which is what a browser asking for `/favicon.ico` should get rather
than a crash.

      public handle(path: String): WebApp {
        if (path == "/tick") {
          this.advance()
        } else if (path == "/w") {
          new WebApp(game, new Up(), seed, ticks)
        } else if (path == "/a") {
          new WebApp(game, new Left(), seed, ticks)
        } else if (path == "/s") {
          new WebApp(game, new Down(), seed, ticks)
        } else if (path == "/d") {
          new WebApp(game, new Right(), seed, ticks)
        } else if (path == "/new") {
          newApp(seed + 17)
        } else {
          this
        }
      }

A tick on a finished game changes nothing. The page keeps refreshing after the
snake dies -- the meta tag has no idea the game ended -- and without this the
dead snake would keep walking.

      private advance(): WebApp {
        if (game.status is Playing) {
          new WebApp(tick(changeDirection(game, dir)), dir, seed, ticks + 1)
        } else {
          this
        }
      }

      public playing(): Boolean { game.status is Playing }

## The page

`render` draws for a terminal: it opens with the escape that clears one and
ends its lines with CR LF. A `<pre>` wants neither.

      public board(): String {
        let afterEscape = render(game).split("\u{1b}[H");
        let body = afterEscape.getOr(afterEscape.length - 1, "");
        body.split("\r\n").join("\n") { s => s }
      }

      public page(): String {
        let refresh = if (playing()) {
          "<meta http-equiv=\"refresh\" content=\"0.25;url=/tick\">"
        } else {
          ""
        };
        let status = if (playing()) { "playing" } else { "game over" };
        let out = new ListBuilder<String>();
        out.add("<!doctype html><html><head><meta charset=\"utf-8\">");
        out.add("<title>Snake</title>${refresh}<style>${style()}</style>");
        out.add("</head><body><main><h1>Snake</h1>");
        out.add("<p class=\"sub\">${status} \u{b7} tick ${ticks.toString()}");
        out.add(" \u{b7} no javascript on this page</p>");
        out.add("<pre>${board()}</pre>");
        out.add("<nav>");
        out.add("<a href=\"/w\" accesskey=\"w\">up</a>");
        out.add("<a href=\"/a\" accesskey=\"a\">left</a>");
        out.add("<a href=\"/s\" accesskey=\"s\">down</a>");
        out.add("<a href=\"/d\" accesskey=\"d\">right</a>");
        out.add("<a href=\"/new\" class=\"new\">new game</a>");
        out.add("</nav>");
        out.add("<p class=\"note\">The board is redrawn by a");
        out.add(" <code>&lt;meta http-equiv=\"refresh\"&gt;</code>.");
        out.add(" The arrows are links. Nothing here runs in your browser");
        out.add(" but HTML.</p></main></body></html>");
        out.toList().join("") { s => s }
      }
    }

    export let newApp(seed: Int): WebApp {
      new WebApp(newGame(20, 10, seed), new Right(), seed, 0)
    }

## Bits and pieces

    let style(): String {
      let out = new ListBuilder<String>();
      out.add("body{margin:0;background:#14161a;color:#d7dae0;");
      out.add("font:14px/1.5 ui-sans-serif,system-ui,sans-serif}");
      out.add("main{max-width:640px;margin:0 auto;padding:32px 16px}");
      out.add("h1{margin:0 0 4px;font-size:22px;font-weight:600}");
      out.add(".sub{margin:0 0 16px;color:#8b929e;font-size:13px}");
      out.add("pre{margin:0;padding:12px 14px;border:1px solid #2a2f37;");
      out.add("border-radius:8px;background:#0f1115;color:#9ee493;");
      out.add("font:15px/1.05 ui-monospace,Menlo,monospace;letter-spacing:.06em;");
      out.add("display:inline-block}");
      out.add("nav{margin:16px 0 0;display:flex;gap:8px}");
      out.add("nav a{padding:6px 12px;border:1px solid #2a2f37;border-radius:6px;");
      out.add("background:#1b1f26;color:#d7dae0;text-decoration:none}");
      out.add("nav a:hover{background:#232832}");
      out.add("nav a.new{margin-left:auto}");
      out.add(".note{margin:16px 0 0;color:#6a717d;font-size:12px}");
      out.add("code{color:#8b929e}");
      out.toList().join("") { s => s }
    }
