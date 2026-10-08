package lang.temper.be.piet

/**
 * The bottom of the stack and the frame the module's top-level code runs in.
 * [entry] pushes N = 0 and [globalCount] zeroes; [main] is top-level code,
 * whose frame starts empty above them.
 */
class MainWriter(val program: AsmProgram, val globalCount: Int) {
    val entry = Label("entry")
    val mainLabel = Label("main")
    val e = Emitter(program)
    val frame = Frame(e, globalCount)

    init {
        e.startBlock(entry, 0)
        repeat(globalCount + 1) { e.const(0) }
        e.adjust(-(globalCount + 1))
        e.startBlock(mainLabel, 0)
    }

    fun halt() {
        e.jumpAway(program.halt)
    }

    /** A string literal, allocated where it is evaluated. */
    fun stringLiteral(frame: Frame, s: String) = frame.stringLiteral(s)
}

fun Frame.stringLiteral(s: String) {
    val codePoints = buildList {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                add(((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000)
                i += 2
            } else {
                add(c.code)
                i += 1
            }
        }
    }
    e.const(codePoints.size)
    for (cp in codePoints) e.const(cp)
    alloc(codePoints.size + 1)
}
