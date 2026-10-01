# Lexing Blimp

This follows `blimp/chunks/lang/src/lexer.zig` rule for rule, with one
difference: Blimp's lexer throws away whitespace and comments, and a
highlighter cannot. So there are three extra kinds, `space`, `newline` and
`comment`, and every character of the source lands in exactly one token.
Every other kind is spelled the way `token.zig` spells it, so the two token
streams can be compared line for line.

    export class Token(public kind: String, public text: String) {}

    export let isTrivia(t: Token): Boolean {
      t.kind == "space" || t.kind == "newline" || t.kind == "comment"
    }

Blimp classifies bytes, not code points, and only ASCII letters, digits and
`_` can start or continue a name.

    let isDigit(c: Int): Boolean { c >= char'0' && c <= char'9' }
    let isUpper(c: Int): Boolean { c >= char'A' && c <= char'Z' }
    let isAlpha(c: Int): Boolean {
      (c >= char'a' && c <= char'z') || isUpper(c) || c == char'_'
    }
    let isAlphaNumeric(c: Int): Boolean { isAlpha(c) || isDigit(c) }

`at` answers -1 past the end, which no test above matches, so a lookahead
never needs its own bounds check.

    let at(s: String, i: StringIndex): Int {
      if (s.hasIndex(i)) { s[i] } else { -1 }
    }

The 28 words `token.zig` turns into keywords, and the kind each becomes.

    let keywordKinds = new Map<String, String>([
      new Pair("actor", "kw_actor"), new Pair("do", "kw_do"),
      new Pair("end", "kw_end"), new Pair("state", "kw_state"),
      new Pair("on", "kw_on"), new Pair("become", "kw_become"),
      new Pair("reply", "kw_reply"), new Pair("when", "kw_when"),
      new Pair("bubbles", "kw_bubbles"), new Pair("bubble", "kw_bubble"),
      new Pair("def", "kw_def"), new Pair("fn", "kw_fn"),
      new Pair("situation", "kw_situation"), new Pair("case", "kw_case"),
      new Pair("orelse", "kw_orelse"), new Pair("spawn", "kw_spawn"),
      new Pair("self", "kw_self"), new Pair("try", "kw_try"),
      new Pair("catch", "kw_catch"), new Pair("for", "kw_for"),
      new Pair("in", "kw_in"), new Pair("test", "kw_test"),
      new Pair("and", "kw_and"), new Pair("or", "kw_or"),
      new Pair("property", "kw_property"), new Pair("given", "kw_given"),
      new Pair("true", "true_lit"), new Pair("false", "false_lit"),
      new Pair("nil", "nil_lit"),
    ]);

    export let lex(source: String): List<Token> {
      let out = new ListBuilder<Token>();
      var i = String.begin;
      while (source.hasIndex(i)) {
        let end = scanOne(source, i);
        out.add(new Token(kindOf(source, i, end), source.slice(i, end)));
        i = end;
      }
      out.toList()
    }

`scanOne` finds where the token starting at `i` ends; `kindOf` names it.
Splitting the two keeps the scanning rules in one place and the naming rules
in another, the way `lexOperator` mixes them is the hardest part of the Zig to
read.

    let scanOne(s: String, i: StringIndex): StringIndex {
      let c = s[i];
      let next = s.next(i);
      if (c == char' ' || c == char'\t' || c == char'\r') {
        var j = next;
        while (at(s, j) == char' ' || at(s, j) == char'\t' || at(s, j) == char'\r') {
          j = s.next(j);
        }
        return j;
      }
      if (c == char'\n') { return next; }
      if (c == char'#') {
        var j = next;
        while (s.hasIndex(j) && s[j] != char'\n') { j = s.next(j); }
        return j;
      }
      if (c == char'"') {
        var j = next;
        while (s.hasIndex(j) && s[j] != char'"') {
          if (s[j] == char'\\') { j = s.next(j); }
          if (s.hasIndex(j)) { j = s.next(j); }
        }
        if (s.hasIndex(j)) { j = s.next(j); }
        return j;
      }
      if (isDigit(c)) { return scanNumber(s, i); }
      if (c == char':') {
        if (at(s, next) == char':') { return s.next(next); }
        if (isAlpha(at(s, next))) { return scanName(s, s.next(next)); }
        return next;
      }
      if (isAlpha(c)) { return scanName(s, next); }
      scanOperator(s, i)
    }

A name runs over letters, digits and `_`, then takes one trailing `?` or `!`.

    let scanName(s: String, from: StringIndex): StringIndex {
      var j = from;
      while (isAlphaNumeric(at(s, j))) { j = s.next(j); }
      if (at(s, j) == char'?' || at(s, j) == char'!') { j = s.next(j); }
      j
    }

Digits, then a fraction only if a digit follows the dot, then an exponent
only if a digit follows the `e` and its optional sign.

    let scanNumber(s: String, i: StringIndex): StringIndex {
      var j = i;
      while (isDigit(at(s, j))) { j = s.next(j); }
      if (at(s, j) == char'.' && isDigit(at(s, s.next(j)))) {
        j = s.next(j);
        while (isDigit(at(s, j))) { j = s.next(j); }
      }
      if (at(s, j) == char'e' || at(s, j) == char'E') {
        var ahead = s.next(j);
        if (at(s, ahead) == char'+' || at(s, ahead) == char'-') { ahead = s.next(ahead); }
        if (isDigit(at(s, ahead))) {
          j = ahead;
          while (isDigit(at(s, j))) { j = s.next(j); }
        }
      }
      j
    }

The operators, longest first. `lexOperator` checks `<--` before any two
character operator, and two character operators before one.

    let operators = [
      "<--", "...", "|>", "<-", "<=", ">=", "==", "!=", "&&", "->", "||", "++", "..",
      "+", "-", "*", "/", "=", "<", ">", "!", "|", ".", "(", ")", "{", "}",
      "[", "]", ",", ":", "%",
    ];

    let scanOperator(s: String, i: StringIndex): StringIndex {
      for (let op of operators) {
        if (startsWithAt(s, i, op)) { return advanceBy(s, i, op); }
      }
      s.next(i)
    }

    let startsWithAt(s: String, i: StringIndex, prefix: String): Boolean {
      var j = i;
      var k = String.begin;
      while (prefix.hasIndex(k)) {
        if (!s.hasIndex(j) || s[j] != prefix[k]) { return false; }
        j = s.next(j);
        k = prefix.next(k);
      }
      true
    }

    let advanceBy(s: String, i: StringIndex, prefix: String): StringIndex {
      var j = i;
      var k = String.begin;
      while (prefix.hasIndex(k)) {
        j = s.next(j);
        k = prefix.next(k);
      }
      j
    }

The kind of the token `s[i..end]`, spelled as in `token.zig`.

    let operatorKinds = new Map<String, String>([
      new Pair("<--", "async_send"), new Pair("...", "dot_dot_dot"),
      new Pair("|>", "pipe_arrow"), new Pair("<-", "send_arrow"),
      new Pair("<=", "lt_eq"), new Pair(">=", "gt_eq"),
      new Pair("==", "eq_eq"), new Pair("!=", "bang_eq"),
      new Pair("&&", "amp_amp"), new Pair("->", "arrow"),
      new Pair("||", "pipe_pipe"), new Pair("++", "plus_plus"),
      new Pair("..", "dot_dot"), new Pair("+", "plus"), new Pair("-", "minus"),
      new Pair("*", "star"), new Pair("/", "slash"), new Pair("=", "eq"),
      new Pair("<", "lt"), new Pair(">", "gt"), new Pair("!", "bang"),
      new Pair("|", "pipe"), new Pair(".", "dot"), new Pair("(", "lparen"),
      new Pair(")", "rparen"), new Pair("{", "lbrace"), new Pair("}", "rbrace"),
      new Pair("[", "lbracket"), new Pair("]", "rbracket"),
      new Pair(",", "comma"), new Pair(":", "colon"), new Pair("::", "colon_colon"),
      new Pair("%", "percent"),
    ]);

    let kindOf(s: String, i: StringIndex, end: StringIndex): String {
      let c = s[i];
      let text = s.slice(i, end);
      if (c == char' ' || c == char'\t' || c == char'\r') { return "space"; }
      if (c == char'\n') { return "newline"; }
      if (c == char'#') { return "comment"; }
      if (c == char'"') { return "string"; }
      if (isDigit(c)) {
        return if (isFloatText(text)) { "float" } else { "integer" };
      }
      if (c == char':' && text != ":" && text != "::") { return "atom"; }
      if (isAlpha(c)) {
        if (text == "_") { return "hole"; }
        let kw = keywordKinds.getOr(text, "");
        if (kw != "") { return kw; }
        return if (isUpper(c)) { "upper_identifier" } else { "identifier" };
      }
      operatorKinds.getOr(text, "invalid")
    }

A number is a float if its text has a dot or an exponent in it: `scanNumber`
only let one in when a digit followed.

    let isFloatText(text: String): Boolean {
      var j = String.begin;
      while (text.hasIndex(j)) {
        let c = text[j];
        if (c == char'.' || c == char'e' || c == char'E') { return true; }
        j = text.next(j);
      }
      false
    }
