# Tests

The lexer is checked two ways. These tests pin the cases that are easy to get
wrong by reading the lexer instead of running it; `oracle/compare.sh` diffs the
whole token stream against Blimp's own lexer over every `.blimp` file in the
repository.

A token stream written as `kind text` pairs, with trivia left out, so an
expectation reads like the oracle's output.

    let kinds(source: String): String {
      lex(source).filter { (t): Boolean => !isTrivia(t) }
        .join(" | ") { (t): String => "${t.kind} ${t.text}" }
    }

Nothing is lost: gluing the tokens back together gives the source, byte for
byte, including comments, blank lines and text Blimp would reject.

    test("the token stream is the source, cut into pieces") {
      let sources = [
        "",
        "actor A do\n  state n: Int :: 0 # count\nend\n",
        "  \t\r\n\n",
        "x = \"unterminated",
        "# only a comment",
        "é ~ @ ` $",
        "\"# not a comment\" # a comment",
      ];
      for (let s of sources) {
        let glued = lex(s).join("") { (t): String => t.text };
        assert(glued == s) { "round trip of ${s}" }
      }
    }

Keywords are whole words. `actors` is a name, and so is `nil?`: a trailing `?`
or `!` belongs to the name before it.

    test("keywords, names and the Ruby-style suffix") {
      assert(kinds("actor actors end_ nil? empty? go!") ==
        "kw_actor actor | identifier actors | identifier end_ | identifier nil? | identifier empty? | identifier go!"
      ) { kinds("actor actors end_ nil? empty? go!") }
      assert(kinds("true false nil") == "true_lit true | false_lit false | nil_lit nil") {
        kinds("true false nil")
      }
    }

A lone `_` is a hole; `_x` is a name. A capital first letter makes an
`upper_identifier`, which Blimp uses for actor and type names.

    test("holes and upper identifiers") {
      assert(kinds("_ _x Counter __new") ==
        "hole _ | identifier _x | upper_identifier Counter | identifier __new"
      ) { kinds("_ _x Counter __new") }
    }

A colon starts an atom only when a letter follows it. `::` is its own token.

    test("atoms and colons") {
      assert(kinds(":ok :ok? :__set_x : x :: 1") ==
        "atom :ok | atom :ok? | atom :__set_x | colon : | identifier x | colon_colon :: | integer 1"
      ) { kinds(":ok :ok? :__set_x : x :: 1") }
    }

An exponent makes a float only when a digit follows it, so `1e` is the integer
`1` and then a name. A dot makes a float only when a digit follows it, so
`1..3` is a range.

    test("numbers take an exponent only when a digit follows") {
      assert(kinds("1e25 1.0E25 9.5e-3 1e 1e+ 1.5 1..3 7.") ==
        "float 1e25 | float 1.0E25 | float 9.5e-3 | integer 1 | identifier e | integer 1 | identifier e | plus + | float 1.5 | integer 1 | dot_dot .. | integer 3 | integer 7 | dot ."
      ) { kinds("1e25 1.0E25 9.5e-3 1e 1e+ 1.5 1..3 7.") }
    }

Operators take the longest match, and `<--` is checked before `<-`.

    test("operators take the longest match") {
      assert(kinds("<-- <- <= < |> || | ++ + ... .. . -> - == = != ! && %") ==
        "async_send <-- | send_arrow <- | lt_eq <= | lt < | pipe_arrow |> | pipe_pipe || | pipe | | plus_plus ++ | plus + | dot_dot_dot ... | dot_dot .. | dot . | arrow -> | minus - | eq_eq == | eq = | bang_eq != | bang ! | amp_amp && | percent %"
      ) { kinds("<-- <- <= < |> || | ++ + ... .. . -> - == = != ! && %") }
    }

A string runs to its closing quote, a backslash skips the character after it,
and an unterminated string runs to the end of the source, as it does in Blimp.

    test("strings, escapes and a string that never closes") {
      assert(kinds("\"a\\\"b\" \"#\" \"open") ==
        "string \"a\\\"b\" | string \"#\" | string \"open"
      ) { kinds("\"a\\\"b\" \"#\" \"open") }
    }

A character Blimp has no token for is `invalid`, one character at a time.

    test("characters Blimp does not know") {
      assert(kinds("@ ~") == "invalid @ | invalid ~") { kinds("@ ~") }
    }

Styles are what a reader sees. A builtin is an identifier Blimp's registry
defines, a type is an upper identifier, and a comment keeps its text.

    test("styles") {
      let styled(source: String): String {
        lex(source).filter { (t): Boolean => t.kind != "space" }
          .join(" ") { (t): String => "${styleOf(t)}:${t.text}" }
      }
      assert(styled("puts length x Box :go \"s\" 1 # hi") ==
        "builtin:puts builtin:length name:x type:Box atom::go string:\"s\" number:1 comment:# hi"
      ) { styled("puts length x Box :go \"s\" 1 # hi") }
      assert(styled("def fn nil _ <- (") ==
        "keyword:def keyword:fn constant:nil hole:_ operator:<- punctuation:("
      ) { styled("def fn nil _ <- (") }
      assert(styled("join index_of replace") ==
        "builtin:join builtin:index_of builtin:replace"
      ) { styled("join index_of replace") }
    }

HTML output escapes what it has to and wraps every styled token in a span
whose class is its style.

    test("html") {
      assert(toHtml("x <- \"<&>\"") ==
        "<span class=\"bl-name\">x</span> <span class=\"bl-operator\">&lt;-</span> <span class=\"bl-string\">\"&lt;&amp;&gt;\"</span>"
      ) { toHtml("x <- \"<&>\"") }
    }

ANSI output colours styled tokens and resets after each one, so a colour never
leaks into the text that follows.

    test("ansi") {
      let esc = String.fromCodePoint(27);
      assert(toAnsi("do x") == "${esc}[35mdo${esc}[0m x") { toAnsi("do x") }
    }
