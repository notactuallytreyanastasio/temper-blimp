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
class Runtime(private val program: AsmProgram) {
    private val placed = mutableMapOf<String, Label>()

    private fun routine(name: String, paramCount: Int, write: FunctionWriter.() -> Unit): Label =
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
