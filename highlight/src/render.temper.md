# Rendering

A style is what a reader sees, and several token kinds share one. The kinds
stay Blimp's; the styles are this highlighter's.

The names Blimp's interpreter defines as builtins: every `reg.register` literal
in `blimp/chunks/lang/src/builtins.zig` plus its table of sixteen float
functions, 125 in all. They are read off the registry, not a grep for
`register`, which also matches actor templates that unit tests register.

    let builtinNames = [
      "abs", "acos", "actor_name", "append", "asin", "assert", "assert_eq",
      "assert_ne", "atan", "atan2", "blockquote", "bold", "button", "canvas",
      "ceil", "char_at", "char_code", "code", "code_block", "concat",
      "contains", "cos", "cosh", "divider", "downcase", "elem", "empty?",
      "exit", "exp", "expm1", "flat", "floor", "fork", "form", "from_char_code",
      "gen_boolean", "gen_integer", "gen_list", "gen_one_of", "gen_string",
      "grid", "head", "heading", "image", "index_of", "input", "italic", "join",
      "key", "keys", "length", "link", "list", "log", "log10", "log1p", "log2",
      "lookup", "max", "merge", "min", "mount_root", "nil?", "not", "now",
      "now_ms", "option", "pow", "print", "put", "puts", "random", "range",
      "read_file", "read_line", "refute", "rem", "replace", "reverse", "round",
      "row", "seed", "select", "set_at", "sin", "sinh", "size", "sleep_ms",
      "slice", "sort", "split", "sqrt", "stack", "sum", "tail", "tan", "tanh",
      "tcp_accept", "tcp_close", "tcp_connect", "tcp_listen", "tcp_poll",
      "tcp_read", "tcp_set_nonblocking", "tcp_write", "text", "textarea",
      "timer", "to_atom", "to_html", "to_int", "to_string", "type_of", "uniq",
      "upcase", "values", "video", "view_diff", "waitpid", "write_bytes",
      "write_file", "ws_accept_key", "ws_read_frame", "ws_write_frame", "zip"
    ];

    let makeBuiltins(): Map<String, Boolean> {
      let b = new MapBuilder<String, Boolean>();
      for (let n of builtinNames) { b.set(n, true); }
      b.toMap()
    }

    let blimpBuiltins = makeBuiltins();

    export let styleOf(t: Token): String {
      let k = t.kind;
      if (k == "space" || k == "newline") { return "plain"; }
      if (k == "comment" || k == "string" || k == "atom" || k == "hole" || k == "invalid") {
        return k;
      }
      if (k == "integer" || k == "float") { return "number"; }
      if (k == "true_lit" || k == "false_lit" || k == "nil_lit") { return "constant"; }
      if (k == "upper_identifier") { return "type"; }
      if (k == "identifier") {
        return if (blimpBuiltins.getOr(t.text, false)) { "builtin" } else { "name" };
      }
      if (startsWithAt(k, String.begin, "kw_")) { return "keyword"; }
      if (k == "lparen" || k == "rparen" || k == "lbrace" || k == "rbrace" ||
          k == "lbracket" || k == "rbracket" || k == "comma" || k == "colon" || k == "dot") {
        return "punctuation";
      }
      "operator"
    }

## HTML

Every token but whitespace is wrapped in a span whose class is `bl-` plus its
style, so a stylesheet decides the colours. Only `&`, `<` and `>` are escaped:
the text lands between tags, never inside an attribute.

    export let toHtml(source: String): String {
      lex(source).join("") { (t): String =>
        let style = styleOf(t);
        if (style == "plain") { t.text } else {
          "<span class=\"bl-${style}\">${escapeHtml(t.text)}</span>"
        }
      }
    }

    let escapeHtml(s: String): String {
      let out = new StringBuilder();
      var i = String.begin;
      while (s.hasIndex(i)) {
        let c = s[i];
        let next = s.next(i);
        if (c == char'&') { out.append("&amp;"); } else if (c == char'<') { out.append("&lt;"); } else if (c == char'>') { out.append("&gt;"); } else { out.appendBetween(s, i, next); }
        i = next;
      }
      out.toString()
    }

## ANSI

For a terminal. Names, operators and punctuation stay uncoloured so the
coloured tokens carry the structure. Each coloured token is followed by a
reset, so an unterminated string cannot colour the rest of the screen.

    let ansiCode(style: String): String {
      if (style == "keyword") { "35" } else if (style == "atom") { "36" } else if (style == "string") { "32" } else if (style == "number" || style == "constant") { "33" } else if (style == "comment") { "90" } else if (style == "type") { "34" } else if (style == "builtin") { "96" } else if (style == "hole") { "91" } else if (style == "invalid") { "41" } else { "" }
    }

    export let toAnsi(source: String): String {
      let esc = String.fromCodePoint(27);
      lex(source).join("") { (t): String =>
        let code = ansiCode(styleOf(t));
        if (code == "") { t.text } else { "${esc}[${code}m${t.text}${esc}[0m" }
      }
    }

## Comparing with Blimp

The significant tokens in the format `oracle/dump` prints for Blimp's own
lexer: kind, a tab, the text with newlines and tabs escaped, one per line.

    export let dump(source: String): String {
      let out = new StringBuilder();
      for (let t of lex(source)) {
        if (!isTrivia(t)) {
          out.append(t.kind);
          out.append("\t");
          var i = String.begin;
          while (t.text.hasIndex(i)) {
            let c = t.text[i];
            let next = t.text.next(i);
            if (c == char'\n') { out.append("\\n"); } else if (c == char'\t') { out.append("\\t"); } else { out.appendBetween(t.text, i, next); }
            i = next;
          }
          out.append("\n");
        }
      }
      out.toString()
    }
