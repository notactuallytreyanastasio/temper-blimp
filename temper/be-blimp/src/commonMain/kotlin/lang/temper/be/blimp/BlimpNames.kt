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
internal val blimpKeywords = setOf(
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
internal val blimpBuiltins = setOf(
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
    // `join`, `index_of` and `replace` joined builtins.zig after this list was
    // read. `map`, `filter`, `reduce` and `each` were never in its registry:
    // eval.zig dispatches them by name after searching the environment, so a
    // def or a local closure of the same name wins over them too. temper-core
    // calls `filter`, `join` and `index_of` directly, so a Temper export called
    // `filter` would have turned every list filter in the program into a call
    // to it.
    "each", "filter", "index_of", "join", "map", "reduce", "replace",
    // Read off Blimp main at 01b5e2c (2026-10-02), 63 names this list had
    // fallen behind by: the crypto and encoding builtins, json, graphemes and
    // utf8, http and the WebSocket client, sleep_ms and read_line (which
    // temper-core itself calls), and the browser's view and effects -- el,
    // draw, fetch, location_query, and from this week show, stored, store,
    // socket and utc_offset. blimp_eval, blimp_test, runtime_snapshot,
    // schedule and show are eval.zig's own, dispatched by name like map.
    // BlimpNamesTest reads blimp/'s builtins.zig and eval.zig and fails
    // when a name there is missing here.
    "aes128gcm_decrypt", "aes128gcm_encrypt", "argv", "base64_decode", "base64_encode",
    "base64url_decode", "base64url_encode", "blimp_eval", "blimp_test", "draw", "ecdsa_p256_sign",
    "ecdsa_p256_verify", "el", "fetch", "file_size", "format_time", "getenv", "grapheme_length",
    "grapheme_slice", "grapheme_take", "graphemes", "hex_decode", "hex_encode", "hmac_sha256",
    "http_result", "http_start", "json_decode", "json_encode", "list_dir", "location_query",
    "now_ms", "p256_ecdh", "p256_keypair", "p256_public_key", "process_stats", "random_bytes",
    "random_token", "read_line", "runtime_snapshot", "schedule", "sha256", "show", "sleep_ms",
    "socket", "sort_by_keys", "store", "stored", "tcp_connect", "tcp_poll_write", "tcp_write_some",
    "utc_offset", "utf8_downcase", "utf8_length", "utf8_scrub", "utf8_slice", "utf8_upcase",
    "utf8_valid", "ws_close", "ws_open", "ws_recv", "ws_send", "ws_stats", "xor_bytes",
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
