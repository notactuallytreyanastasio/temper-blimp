package lang.temper.be.piet

/**
 * Piet assembly, for a library's own implementations of its @connected
 * functions. A backend with a host language takes those from a file in
 * it (be-lua's `_connected.lua`, be-blimp's `_connected.blimp`). Piet's
 * own form is an image, which nobody writes by hand, so be-piet reads a
 * `_connected.pasm` beside the module source instead:
 *
 * ```
 * # i + j + bonus, where an omitted bonus arrives as null
 * fn sum(i, j, bonus):
 *     i j add
 *     bonus isnull if 0 else bonus heap0 end
 *     add ret
 * ```
 *
 * A routine is a run of words in the [Frame] calling convention: a
 * parameter's name pushes it, an integer pushes itself, a Piet command
 * name runs it (add sub mul div mod not gt dup pop swap), `heapN` replaces
 * an address with the cell N after it (an object's fields start at 1, a
 * string's length is at 0), `null` and `isnull` deal in -1, `if ... else
 * ... end` branches on the top, and `ret` / `retvoid` return. A value is
 * represented as everywhere in be-piet: a nullable Int, or an optional Int
 * parameter, is a box (see PietTranslator.coerce).
 */
class Pasm(source: String) {
    class Routine(val name: String, val params: List<String>, val words: List<String>)

    val routines: Map<String, Routine> = parse(source).associateBy { it.name }

    private fun parse(source: String): List<Routine> {
        val out = mutableListOf<Routine>()
        var name: String? = null
        var params = listOf<String>()
        var words = mutableListOf<String>()
        fun flush() {
            name?.let { out.add(Routine(it, params, words)) }
        }
        for (rawLine in source.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("fn ")) {
                flush()
                val header = line.removePrefix("fn ").removeSuffix(":").trim()
                name = header.substringBefore('(').trim()
                params = header.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
                    .filter { it.isNotEmpty() }
                words = mutableListOf()
            } else {
                require(name != null) { "pasm: code before any fn: $line" }
                words.addAll(line.split(Regex("\\s+")))
            }
        }
        flush()
        return out
    }

    /** Assembles [routine] into [writer], whose frame has the routine's parameters. */
    fun assemble(routine: Routine, writer: FunctionWriter) {
        val e = writer.e
        val frame = writer.frame
        writer.begin(0)
        val ops = mapOf(
            "add" to PietOp.Add, "sub" to PietOp.Subtract, "mul" to PietOp.Multiply,
            "div" to PietOp.Divide, "mod" to PietOp.Mod, "not" to PietOp.Not,
            "gt" to PietOp.Greater, "dup" to PietOp.Duplicate, "pop" to PietOp.Pop,
        )
        // if/else/end: a stack of (else label, end label, depth at the if)
        data class Open(val otherwise: Label, val end: Label, val depth: Int, var inElse: Boolean = false)
        val open = ArrayDeque<Open>()
        var n = 0
        for (word in routine.words) {
            val param = routine.params.indexOf(word)
            when {
                param >= 0 -> frame.load(writer.param(param))
                word.toLongOrNull() != null -> e.const(word.toLong())
                word in ops -> e.op(ops.getValue(word))
                word == "swap" -> e.swap()
                word.startsWith("heap") && word.removePrefix("heap").toIntOrNull() != null ->
                    frame.heapLoad(word.removePrefix("heap").toInt())
                word == "null" -> e.const(PietTranslator.NULL)
                word == "isnull" -> {
                    e.const(PietTranslator.NULL)
                    e.op(PietOp.Subtract)
                    e.op(PietOp.Not)
                }
                word == "if" -> {
                    val then = writer.label("then${n++}")
                    val o = Open(writer.label("else$n"), writer.label("end$n"), e.depth - 1)
                    e.branch(then, o.otherwise)
                    e.startBlock(then, o.depth)
                    open.addLast(o)
                }
                word == "else" -> {
                    val o = open.last()
                    e.jump(o.end)
                    e.startBlock(o.otherwise, o.depth)
                    o.inElse = true
                }
                word == "end" -> {
                    val o = open.removeLast()
                    e.jump(o.end)
                    if (!o.inElse) {
                        e.startBlock(o.otherwise, o.depth)
                        e.jump(o.end)
                    }
                    e.startBlock(o.end)
                }
                word == "ret" -> writer.ret(true)
                word == "retvoid" -> writer.ret(false)
                else -> error("pasm: unknown word `$word` in ${routine.name}")
            }
        }
        check(open.isEmpty()) { "pasm: unclosed if in ${routine.name}" }
        check(!e.reachable) { "pasm: ${routine.name} does not end with ret" }
    }

    companion object {
        const val CONNECTED_FILE = "_connected.pasm"
    }
}
