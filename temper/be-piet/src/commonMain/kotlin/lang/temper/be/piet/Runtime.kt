package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.OutChar
import lang.temper.be.piet.PietOp.Pop
import lang.temper.be.piet.PietOp.Subtract

/**
 * Routines written by hand in the [Frame] calling convention, each placed in
 * the program the first time something asks for it.
 *
 * A string is a heap address `a` with its length at h(a) and its code points
 * at h(a+1) .. h(a+len).
 */
class Runtime(val program: AsmProgram) {
    private val placed = mutableMapOf<String, Label>()

    /** The global holding async's run queue, when the program uses async; see Async.kt. */
    var runQueueGlobal: Int = -1

    init {
        // Placed up front: every call that cannot bubble names it as its failure row.
        program.unexpectedFailure = panic
    }

    /** A block outside any frame, placed once: for code that is jumped to and never returns. */
    internal fun placedBlock(name: String, write: (Emitter) -> Unit): Label = placed.getOrPut(name) {
        val label = Label("rt_$name")
        val e = Emitter(program)
        e.startBlock(label, 1)
        write(e)
        label
    }

    internal fun routine(name: String, paramCount: Int, write: FunctionWriter.() -> Unit): Label =
        placed.getOrPut(name) {
            val writer = FunctionWriter(program, "rt_$name", paramCount)
            writer.write()
            writer.entry
        }

    /**
     * Writes one code point as UTF-8. npiet's out(char) writes only the low
     * byte of its argument, so the bytes are computed here.
     */
    val putCodePoint: Label get() = routine("putc", 1) {
        begin(0)
        val c = param(0)
        fun out(divisor: Int, mask: Int, base: Int) {
            frame.load(c)
            if (divisor != 1) {
                e.const(divisor)
                e.op(Divide)
            }
            if (mask != 0) {
                e.const(mask)
                e.op(Mod)
            }
            e.const(base)
            e.op(Add)
            e.op(OutChar)
        }
        fun lessThan(limit: Int) {
            e.const(limit)
            frame.load(c)
            e.op(Greater)
        }
        e.ifElse("putc1", { lessThan(0x80) }, { out(1, 0, 0) }) {
            e.ifElse("putc2", { lessThan(0x800) }, {
                out(64, 0, 0xC0)
                out(1, 64, 0x80)
            }) {
                e.ifElse("putc3", { lessThan(0x10000) }, {
                    out(4096, 0, 0xE0)
                    out(64, 64, 0x80)
                    out(1, 64, 0x80)
                }) {
                    out(262144, 0, 0xF0)
                    out(4096, 64, 0x80)
                    out(64, 64, 0x80)
                    out(1, 64, 0x80)
                }
            }
        }
        ret(false)
    }

    /** Writes a string and a newline. */
    val log: Label get() {
        val putc = putCodePoint
        return routine("log", 1) {
            begin(2)
            val s = param(0)
            val i = param(1)
            val n = param(2)
            frame.load(s)
            frame.heapLoad()
            frame.store(n)
            e.const(1)
            frame.store(i)
            e.whileLoop("log.chars", { notGreater(i, n) }) {
                call(frame, putc, 1, false) {
                    frame.load(s)
                    frame.load(i)
                    e.op(Add)
                    frame.heapLoad()
                }
                increment(i)
            }
            e.const('\n'.code)
            e.op(OutChar)
            ret(false)
        }
    }

    /**
     * Copies a string's code points to the top of the heap. Concatenation
     * makes its header cell, appends each part, then fills in the length;
     * the parts are contiguous because nothing else allocates in between.
     */
    val appendChars: Label get() = routine("append", 1) {
        begin(2)
        val s = param(0)
        val i = param(1)
        val n = param(2)
        frame.load(s)
        frame.heapLoad()
        frame.store(n)
        e.const(1)
        frame.store(i)
        e.whileLoop("append.chars", { notGreater(i, n) }) {
            frame.load(s)
            frame.load(i)
            e.op(Add)
            frame.heapLoad()
            frame.append()
            increment(i)
        }
        ret(false)
    }

    /**
     * Decimal digits of an integer, as a new string.
     *
     * The digits come from -|n|, not |n|: |-2^31| does not fit in 32 bits,
     * and npiet's roll keeps only 32 bits of the value it moves, so it would
     * not survive being stored. This relies on npiet's division and mod
     * truncating toward zero, as C's do.
     */
    val intToString: Label get() = routine("itoa", 1) {
        begin(3)
        val n = param(0)
        val neg = param(1)
        val place = param(2)
        val header = param(3)
        frame.load(n)
        frame.store(neg)
        e.ifThen("itoa.pos", { isNegative(n); e.op(Not) }) {
            frame.load(n)
            e.const(-1)
            e.op(Multiply)
            frame.store(neg)
        }
        // The largest power of ten not above |n|, and at least 1.
        e.const(1)
        frame.store(place)
        e.whileLoop("itoa.place", {
            frame.load(place)
            e.const(10)
            e.op(Multiply)
            frame.load(neg)
            e.const(-1)
            e.op(Multiply)
            e.op(Greater)
            e.op(Not)
        }) {
            frame.load(place)
            e.const(10)
            e.op(Multiply)
            frame.store(place)
        }
        e.const(0)
        frame.alloc(1)
        frame.store(header)
        e.ifThen("itoa.sign", { isNegative(n) }) {
            e.const('-'.code)
            frame.append()
        }
        e.whileLoop("itoa.digits", {
            frame.load(place)
            e.const(0)
            e.op(Greater)
        }) {
            // '0' - (neg / place) % 10
            e.const('0'.code)
            frame.load(neg)
            frame.load(place)
            e.op(Divide)
            e.const(10)
            e.op(Mod)
            e.op(Subtract)
            frame.append()
            frame.load(place)
            e.const(10)
            e.op(Divide)
            frame.store(place)
        }
        fillLength(header)
        frame.load(header)
        this@Runtime.internTop(frame)
        ret(true)
    }

    private fun FunctionWriter.notGreater(a: Int, b: Int) {
        frame.load(a)
        frame.load(b)
        e.op(Greater)
        e.op(Not)
    }

    private fun FunctionWriter.increment(slot: Int) {
        frame.load(slot)
        e.const(1)
        e.op(Add)
        frame.store(slot)
    }

    private fun FunctionWriter.isNegative(slot: Int) {
        e.const(0)
        frame.load(slot)
        e.op(Greater)
    }

    private fun FunctionWriter.fillLength(header: Int) = frame.fillLength { frame.load(header) }
}

/**
 * Stores, at the header cell [loadHeader] pushes, the number of cells
 * allocated since it: `N - header - 1`.
 */
fun Frame.fillLength(loadHeader: () -> Unit) {
    loadHeapSize()
    loadHeader()
    e.op(Subtract)
    e.const(1)
    e.op(Subtract)
    loadHeader()
    heapStore()
}

/** Drops [count] values from under the top one. */
fun Emitter.dropUnder(count: Int) {
    if (count == 0) return
    const(count + 1)
    const(1)
    op(PietOp.Roll)
    repeat(count) { op(Pop) }
}

/** -1, 0 or 1, comparing two strings code point by code point. */
val Runtime.stringCompare: Label get() = routine("str_cmp", 2) {
    begin(2)
    val a = param(0)
    val b = param(1)
    val i = param(2)
    val d = param(3)
    fun sign() {
        // [x] -> (x > 0) - (0 > x)
        e.op(PietOp.Duplicate)
        e.const(0)
        e.op(PietOp.Greater)
        e.swap()
        e.const(0)
        e.swap()
        e.op(PietOp.Greater)
        e.op(PietOp.Subtract)
    }
    e.const(1)
    frame.store(i)
    e.loop(label("chars").hint, {
        // i <= len a && i <= len b
        frame.load(i)
        frame.load(a)
        frame.heapLoad()
        e.op(PietOp.Greater)
        frame.load(i)
        frame.load(b)
        frame.heapLoad()
        e.op(PietOp.Greater)
        e.op(PietOp.Add)
        e.op(PietOp.Not)
    }) { exit ->
        frame.load(a)
        frame.load(i)
        e.op(PietOp.Add)
        frame.heapLoad()
        frame.load(b)
        frame.load(i)
        e.op(PietOp.Add)
        frame.heapLoad()
        e.op(PietOp.Subtract)
        frame.store(d)
        val same = label("same")
        frame.load(d)
        e.op(PietOp.Not)
        e.branch(same, exit)
        e.startBlock(same)
        frame.load(i)
        e.const(1)
        e.op(PietOp.Add)
        frame.store(i)
    }
    e.ifThen(label("tail").hint, {
        frame.load(d)
        e.op(PietOp.Not)
    }) {
        frame.load(a)
        frame.heapLoad()
        frame.load(b)
        frame.heapLoad()
        e.op(PietOp.Subtract)
        frame.store(d)
    }
    frame.load(d)
    sign()
    ret(true)
}

/**
 * 32-bit and, or, xor, one bit at a time from the bottom. Each bit's result
 * is `c1 * (a + b) + c2 * a * b`. Bit 31 weighs -2^31, so every partial
 * result fits in 32 bits and survives being stored.
 *
 * Unrolled: a loop would jump through the trunk 32 times per call, and a
 * jump costs as much as all of a bit's arithmetic many times over.
 */
fun Runtime.bitwise(name: String, c1: Int, c2: Int): Label = routine("bit_$name", 2) {
    begin(1)
    val a = param(0)
    val b = param(1)
    val r = param(2)
    fun lowBit(slot: Int) {
        // ((x mod 2) + 2) mod 2, then x = (x - bit) / 2
        frame.load(slot)
        e.const(2)
        e.op(PietOp.Mod)
        e.const(2)
        e.op(PietOp.Add)
        e.const(2)
        e.op(PietOp.Mod)
        e.op(PietOp.Duplicate)
        frame.load(slot)
        e.swap()
        e.op(PietOp.Subtract)
        e.const(2)
        e.op(PietOp.Divide)
        frame.store(slot)
    }
    for (i in 0 until 32) {
        lowBit(a)
        lowBit(b)
        // [x, y] -> c1 * (x + y) + c2 * x * y
        e.pick(2)
        e.pick(2)
        e.op(PietOp.Add)
        e.const(c1)
        e.op(PietOp.Multiply)
        e.pick(3)
        e.pick(3)
        e.op(PietOp.Multiply)
        e.const(c2)
        e.op(PietOp.Multiply)
        e.op(PietOp.Add)
        e.dropUnder(2)
        e.const(if (i == 31) -(1L shl 31) else 1L shl i)
        e.op(PietOp.Multiply)
        frame.load(r)
        e.op(PietOp.Add)
        frame.store(r)
    }
    frame.load(r)
    ret(true)
}

/**
 * Piet has no stderr and no exit status: npiet exits 0 however a program
 * ends. A panic therefore writes [PANIC_MARKER] to stdout and halts, and
 * the runner turns output carrying it into a failed run.
 */
const val PANIC_MARKER = "\u0001PANIC"

val Runtime.panic: Label get() = routine("panic", 0) {
    begin(0)
    // Reached as the failure row of a call that was not expected to bubble.
    for (c in "$PANIC_MARKER: an unexpected bubble") {
        e.const(c.code)
        e.op(OutChar)
    }
    e.jumpAway(program.halt)
}

/** `[v] -> [box of v, or null]`: a nullable value entering a generic `T?`. */
val Runtime.boxIfNotNull: Label get() = routine("box_nn", 1) {
    begin(0)
    e.ifElseValue(label("null").hint, {
        frame.load(param(0))
        e.const(PietTranslator.NULL)
        e.op(PietOp.Subtract)
        e.op(PietOp.Not)
    }, { e.const(PietTranslator.NULL) }) {
        frame.load(param(0))
        frame.alloc(1)
    }
    ret(true)
}

/** `[box or null] -> [v or null]`: a generic `T?` leaving for a concrete nullable type. */
val Runtime.unboxIfNotNull: Label get() = routine("unbox_nn", 1) {
    begin(0)
    e.ifElseValue(label("null").hint, {
        frame.load(param(0))
        e.const(PietTranslator.NULL)
        e.op(PietOp.Subtract)
        e.op(PietOp.Not)
    }, { e.const(PietTranslator.NULL) }) {
        frame.load(param(0))
        frame.heapLoad()
    }
    ret(true)
}

/**
 * A panic that says where: jumped to (not called) with a site number on
 * top of the stack, which it writes after the marker. PietTranslator keeps
 * the numbers' meanings, printed with its debug dump.
 */
val Runtime.panicAt: Label get() = placedBlock("panic_at") { e ->
    for (c in "$PANIC_MARKER at site ") {
        e.const(c.code)
        e.op(OutChar)
    }
    e.op(PietOp.OutNumber)
    e.jumpAway(program.halt)
}

/** A generator is a block `fn, done`, where fn is the state machine Temper made of a generator function. */
const val GEN_FN = 0
const val GEN_DONE = 1

/**
 * `[generator] -> [result]`. A ValueResult is a box holding the value and
 * a DoneResult is null, so the state machine's own result is the answer;
 * the generator is done once that is null, and fn is not called again.
 */
val Runtime.generatorNext: Label get() = routine("gen_next", 1) {
    begin(0)
    e.ifElseValue(label("done").hint, {
        frame.load(param(0))
        frame.heapLoad(GEN_DONE)
    }, { e.const(PietTranslator.NULL) }) {
        // The state machine takes its own generator.
        callClosure(frame, 1, true, null, {
            frame.load(param(0))
            frame.heapLoad(GEN_FN)
        }) { frame.load(param(0)) }
        e.ifThen(label("finished").hint, {
            e.op(PietOp.Duplicate)
            e.const(PietTranslator.NULL)
            e.op(PietOp.Subtract)
            e.op(PietOp.Not)
        }) {
            e.const(1)
            frame.load(param(0))
            frame.heapStore(GEN_DONE)
        }
    }
    ret(true)
}
