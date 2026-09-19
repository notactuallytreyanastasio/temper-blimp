package lang.temper.be.blimp

import lang.temper.name.ExportedName
import lang.temper.name.OutName
import lang.temper.name.ResolvedName
import lang.temper.name.Temporary

/**
 * Blimp's reserved words, read off `Token.keyword` in the interpreter's lexer.
 *
 * Note what is absent: `not`, `if`, `while`, `return` and `import` are not
 * keywords, because Blimp has no such constructs.
 */
private val blimpKeywords = setOf(
    "actor", "and", "become", "bubble", "bubbles", "case", "catch", "def", "do", "end", "false", "fn",
    "for", "given", "in", "nil", "on", "or", "orelse", "property", "reply", "self", "situation",
    "spawn", "state", "test", "true", "try", "when",
)

/**
 * Blimp's builtin functions, read off the `reg.register` calls in the
 * interpreter's `builtins.zig`.
 *
 * These are not keywords, so nothing stops a program from defining one -- and
 * a definition wins. That is what a Temper library exporting `length` did: the
 * backend emitted `def length(s) do connected_length(s) end` at the top level,
 * `connected_length` called `length`, and because the wrapper had shadowed the
 * builtin for the whole file it called itself. Tail calls do not grow the
 * stack, so it did not crash; it spun at 100% CPU until the test harness was
 * killed.
 *
 * The shadowing is not limited to top level either: a Blimp closure sees its
 * caller's locals, so a local called `length` hides the builtin from anything
 * it calls. Every name goes through [BlimpNames.sanitize], so every name is
 * covered.
 *
 * `nil?` and `empty?` are registered too and are deliberately absent:
 * [notIdentifierChar] rewrites `?` to `_` before this set is consulted, so a
 * Temper name can never come out spelled like either of them.
 */
private val blimpBuiltins = setOf(
    "abs", "acos", "actor_name", "append", "asin", "assert",
    "assert_eq", "assert_ne", "atan", "atan2", "blockquote", "bold", "button", "canvas", "ceil",
    "char_at", "char_code", "code", "code_block", "concat", "contains", "cos", "cosh", "divider",
    "downcase", "elem", "exit", "exp", "expm1", "flat", "floor", "fork", "form", "from_char_code",
    "gen_boolean", "gen_integer", "gen_list", "gen_one_of", "gen_string", "grid", "head",
    "heading", "image", "input", "italic", "key", "keys", "length", "link", "list", "log", "log10",
    "log1p", "log2", "lookup", "max", "merge", "min", "mount_root", "not", "now", "option", "pow",
    "print", "put", "puts", "random", "range", "read_file", "refute", "rem", "reverse", "round",
    "row", "seed", "select", "set_at", "sin", "sinh", "size", "slice", "sort", "split", "sqrt",
    "stack", "sum", "tail", "tan", "tanh", "tcp_accept", "tcp_close", "tcp_listen", "tcp_poll",
    "tcp_read", "tcp_set_nonblocking", "tcp_write", "text", "textarea", "timer", "to_atom",
    "to_html", "to_int", "to_string", "type_of", "uniq", "upcase", "values", "video", "view_diff",
    "waitpid", "write_bytes", "write_file", "ws_accept_key", "ws_read_frame", "ws_write_frame",
    "zip",
)

/** Blimp identifiers are ASCII letters, digits and underscore, not starting with a digit. */
private val notIdentifierChar = Regex("[^A-Za-z0-9_]")

/**
 * Makes Blimp identifiers, keeping them stable and collision-free.
 *
 * Blimp has no module system, so every name in a translated program shares one
 * flat namespace. TmpL names already carry a disambiguating suffix (`a__4`),
 * which is what keeps that workable.
 *
 * [gensym] has no such suffix to lean on, only a counter, so one instance has
 * to cover everything that lands in one file -- which is every module of every
 * library in the dependency graph, not one module. It is built once in
 * [BlimpBackend.translate] and passed to each module's translator.
 */
internal class BlimpNames {
    private var gensymCount = 0

    fun outName(name: ResolvedName): OutName = OutName(identText(name), name)

    /** A fresh name that cannot collide with a translated one, for hoisted temporaries. */
    fun gensym(hint: String): OutName = OutName("blimp_${sanitize(hint)}_${gensymCount++}", null)

    private fun identText(name: ResolvedName): String = sanitize(
        when (name) {
            is ExportedName -> name.displayName
            // Three underscores, as be-rust does, so a temporary cannot collide
            // with a user name that happens to end in digits.
            is Temporary -> "${name.nameHint}___${name.uid}"
            else -> "$name"
        },
    )

    private fun sanitize(text: String): String {
        val cleaned = notIdentifierChar.replace(text, "_")
        val legal = when {
            cleaned.isEmpty() -> "_"
            cleaned.first().isDigit() -> "_$cleaned"
            else -> cleaned
        }
        return when (legal) {
            in blimpKeywords, in blimpBuiltins -> "${legal}_"
            else -> legal
        }
    }
}
