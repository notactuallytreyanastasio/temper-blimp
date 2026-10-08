package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Duplicate
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Pop
import lang.temper.be.piet.PietOp.Subtract

/*
 * Float64, in software. Piet has no floating point, so a Float64 is a box
 * holding its IEEE-754 bits, the same two-cell box as an Int64, and each
 * operation works on the bits as integers: unpack the sign, exponent and
 * significand, compute with three extra bits below the significand (guard,
 * round, sticky), and round to nearest, ties to even, when packing.
 *
 * Inside a routine, a 64-bit quantity lives in two 32-bit slots, a [Wide],
 * and is only ever whole on top of the stack, for arithmetic. Nothing wider
 * than 32 bits may be the element a roll moves (npiet keeps 32 bits of it),
 * so minTop, maxTop and swap, which move what they compare, are used here
 * only on exponents and flags.
 *
 * Exponent convention while computing: a significand m with its leading bit
 * at 2^55 and exponent e stand for m / 2^55 * 2^(e - 1023), so e is the
 * biased exponent the result would have if m needed no rounding.
 */

/** Locals for a routine: 32-bit ones, and 64-bit ones made of two slots. */
class Locals(private val w: FunctionWriter) {
    private var count = 0
    private fun slot() = w.param(w.paramCount + count++)

    inner class V internal constructor(val slot: Int) {
        fun get() = w.frame.load(slot)
        fun set(push: () -> Unit) {
            push()
            w.frame.store(slot)
        }
    }

    /** A 64-bit local: hi is the true top half, lo the bottom half as a signed Int. */
    inner class Wide internal constructor(private val hi: Int, private val lo: Int) {
        fun get() {
            val f = w.frame
            f.load(hi)
            w.e.const(TWO_32)
            w.e.op(Multiply)
            f.load(lo)
            w.e.const(TWO_32)
            w.e.op(Add)
            w.e.const(TWO_32)
            w.e.op(Mod)
            w.e.op(Add)
        }

        fun set(push: () -> Unit) {
            push()
            w.e.splitWide()
            w.frame.store(lo)
            w.frame.store(hi)
        }
    }

    fun v() = V(slot())
    fun wide() = Wide(slot(), slot())

    /** Call once every local is declared, before any code. */
    fun begin() = w.begin(count)
}

/** `[v] -> [hi, lo]`: the halves pack64 stores, without allocating. */
fun Emitter.splitWide() {
    op(Duplicate)
    op(Duplicate)
    const(TWO_32)
    op(Mod)
    const(0)
    swap()
    op(Greater)
    const(2)
    const(1)
    op(PietOp.Roll)
    const(TWO_32)
    op(Divide)
    const(2)
    const(1)
    op(PietOp.Roll)
    op(Subtract)
    op(Duplicate)
    const(3)
    const(1)
    op(PietOp.Roll)
    const(TWO_32)
    op(Multiply)
    op(Subtract)
    op(Duplicate)
    const(Int.MAX_VALUE.toLong())
    op(Greater)
    const(TWO_32)
    op(Multiply)
    op(Subtract)
}

/** `[v] -> [floor(v / d)]`, d pushed by [divisor] twice; v may be wide, d at most 2^62. */
fun Emitter.floorDiv(divisor: () -> Unit) {
    op(Duplicate)
    divisor()
    op(Mod)
    const(0)
    swap()
    op(Greater)
    swap()
    divisor()
    op(Divide)
    const(2)
    const(-1)
    op(PietOp.Roll)
    op(Subtract)
}

/** `[v] -> [v mod d]` in [0, d). */
fun Emitter.posMod(d: Long) {
    const(d)
    op(Mod)
    const(d)
    op(Add)
    const(d)
    op(Mod)
}

/**
 * `[m] -> [m >> k | sticky]`, m non-negative and below 2^62, the bits
 * shifted out ORed into bit 0. [shift] pushes k (0..62), twice.
 */
fun Emitter.shiftRightSticky(shift: () -> Unit) {
    // [m] -> [m, nonzero remainder]
    op(Duplicate)
    shift()
    pow2()
    op(Mod)
    op(Not)
    op(Not)
    // [m, s] -> [s, m]: s is small
    swap()
    shift()
    pow2()
    op(Divide)
    // [s, q] -> q + s * (1 - q % 2)
    op(Duplicate)
    const(2)
    op(Mod)
    const(-1)
    op(Multiply)
    const(1)
    op(Add)
    pick(3)
    op(Multiply)
    op(Add)
    const(2)
    const(-1)
    op(PietOp.Roll)
    op(Pop)
}

private const val F_FRAC = 1L shl 52
private const val F_EXP_MASK = 2048L
private const val F_INF_EXP = 2047
private const val CANONICAL_NAN = 0x7FF8000000000000L

/**
 * Inline code generators shared by the float routines. Construct it before
 * [Locals.begin]: it declares the temporaries it needs.
 */
class FloatCode(private val w: FunctionWriter, L: Locals) {
    private val e = w.e
    private val f = w.frame
    private val c = L.v()
    private val g = L.v()

    /** Splits the box in [box] into sign, exponent field and fraction. */
    fun unpack(box: Locals.V, s: Locals.V, ef: Locals.V, frac: Locals.Wide, bits: Locals.Wide) {
        bits.set {
            box.get()
            f.unpack64()
        }
        s.set {
            e.const(0)
            bits.get()
            e.op(Greater)
        }
        ef.set {
            bits.get()
            e.floorDiv { e.const(F_FRAC) }
            e.posMod(F_EXP_MASK)
        }
        frac.set {
            bits.get()
            e.posMod(F_FRAC)
        }
    }

    /** Adds the implicit bit to a normal significand; a subnormal's exponent becomes 1. */
    fun implicit(ef: Locals.V, m: Locals.Wide, ex: Locals.V) {
        m.set {
            m.get()
            ef.get()
            e.const(0)
            e.op(Greater)
            e.const(F_FRAC)
            e.op(Multiply)
            e.op(Add)
        }
        ex.set {
            ef.get()
            ef.get()
            e.const(0)
            e.op(Greater)
            e.op(Not)
            e.op(Add)
        }
    }

    /** Shifts m (nonzero, below 2^(top+1)) left until bit [top] is set, lowering ex to match. No branch. */
    fun normalize(m: Locals.Wide, ex: Locals.V, top: Int) {
        for (sh in intArrayOf(32, 16, 8, 4, 2, 1)) {
            c.set {
                e.const(1L shl (top + 1 - sh))
                m.get()
                e.op(Greater)
            }
            m.set {
                m.get()
                c.get()
                e.const((1L shl sh) - 1)
                e.op(Multiply)
                e.const(1)
                e.op(Add)
                e.op(Multiply)
            }
            ex.set {
                ex.get()
                c.get()
                e.const(sh)
                e.op(Multiply)
                e.op(Subtract)
            }
        }
    }

    /**
     * Rounds m (leading bit at 2^55, or below for a subnormal) with exponent
     * ex and sign s to the nearest double, ties to even, and pushes its box.
     * Handles results too small (subnormal or zero) and too large (infinity).
     * No branch.
     */
    fun roundPack(s: Locals.V, ex: Locals.V, m: Locals.Wide) {
        // Too small: shift right by 1 - ex, sticky; ex becomes 1.
        m.set {
            m.get()
            e.shiftRightSticky {
                e.const(1)
                ex.get()
                e.op(Subtract)
                e.const(0)
                e.maxTop()
                e.const(62)
                e.minTop()
            }
        }
        ex.set {
            ex.get()
            e.const(1)
            e.maxTop()
        }
        // Round: drop the three extra bits, adding 1 for more than half, or
        // exactly half with an odd result.
        g.set {
            m.get()
            e.const(8)
            e.op(Mod)
        }
        m.set {
            m.get()
            e.const(8)
            e.op(Divide)
        }
        m.set {
            m.get()
            g.get()
            e.const(4)
            e.op(Greater)
            g.get()
            e.const(4)
            e.op(Subtract)
            e.op(Not)
            m.get()
            e.const(2)
            e.op(Mod)
            e.op(Multiply)
            e.op(Add)
            e.op(Add)
        }
        // Rounding up to 2^53 carries into the exponent.
        c.set {
            m.get()
            e.const(1L shl 53)
            e.op(Subtract)
            e.op(Not)
        }
        m.set {
            m.get()
            c.get()
            e.const(F_FRAC)
            e.op(Multiply)
            e.op(Subtract)
        }
        ex.set {
            ex.get()
            c.get()
            e.op(Add)
        }
        // Exponent field: 0 for a subnormal (m below 2^52).
        c.set {
            m.get()
            e.const(F_FRAC - 1)
            e.op(Greater)
        }
        ex.set {
            ex.get()
            c.get()
            e.op(Multiply)
        }
        m.set {
            m.get()
            c.get()
            e.const(F_FRAC)
            e.op(Multiply)
            e.op(Subtract)
        }
        // Overflow: the field saturates at 2047 with no fraction.
        c.set {
            ex.get()
            e.const(F_INF_EXP - 1L)
            e.op(Greater)
        }
        ex.set {
            ex.get()
            c.get()
            e.const(F_INF_EXP)
            ex.get()
            e.op(Subtract)
            e.op(Multiply)
            e.op(Add)
        }
        m.set {
            m.get()
            c.get()
            e.op(Not)
            e.op(Multiply)
        }
        packBits(s, ex, m)
    }

    /** Pushes the box for sign s, exponent field ef, fraction frac. */
    fun packBits(s: Locals.V, ef: Locals.V, frac: Locals.Wide) {
        ef.get()
        e.const(F_FRAC)
        e.op(Multiply)
        frac.get()
        e.op(Add)
        // The sign bit: adding MIN to a non-negative value cannot overflow.
        s.get()
        e.constMin64()
        e.op(Multiply)
        e.op(Add)
        f.pack64()
    }

    fun pushNaN() {
        e.const(CANONICAL_NAN)
        f.pack64()
    }

    fun pushSignedZero(s: Locals.V) {
        s.get()
        e.constMin64()
        e.op(Multiply)
        f.pack64()
    }

    fun pushSignedInfinity(s: Locals.V) {
        e.const(F_INF_EXP.toLong() * F_FRAC)
        s.get()
        e.constMin64()
        e.op(Multiply)
        e.op(Add)
        f.pack64()
    }

    /** Pushes whether a field/fraction pair is NaN. */
    fun isNaN(ef: Locals.V, frac: Locals.Wide) {
        ef.get()
        e.const(F_INF_EXP)
        e.op(Subtract)
        e.op(Not)
        frac.get()
        e.op(Not)
        e.op(Not)
        e.op(Multiply)
    }

    fun isInf(ef: Locals.V, frac: Locals.Wide) {
        ef.get()
        e.const(F_INF_EXP)
        e.op(Subtract)
        e.op(Not)
        frac.get()
        e.op(Not)
        e.op(Multiply)
    }

    fun isZero(ef: Locals.V, frac: Locals.Wide) {
        ef.get()
        e.op(Not)
        frac.get()
        e.op(Not)
        e.op(Multiply)
    }
}

/** Runs [then] and returns from the routine with the box it pushes, if [cond] is nonzero. */
private fun FunctionWriter.returnIf(name: String, cond: () -> Unit, then: () -> Unit) {
    val yes = label("$name.yes")
    val no = label("$name.no")
    cond()
    val d0 = e.depth - 1
    e.branch(yes, no)
    e.startBlock(yes, d0)
    then()
    ret(true)
    e.startBlock(no, d0)
}

/** a + b. Subtraction is addition of the negation. */
val Runtime.floatAdd: Label get() = routine("f64_add", 2) {
    val L = Locals(this)
    val a = L.V(param(0))
    val b = L.V(param(1))
    val sa = L.v()
    val ea = L.v()
    val sb = L.v()
    val eb = L.v()
    val t = L.v()
    val ma = L.wide()
    val mb = L.wide()
    val m = L.wide()
    val bits = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, sa, ea, ma, bits)
    fc.unpack(b, sb, eb, mb, bits)
    returnIf("nan", {
        fc.isNaN(ea, ma)
        fc.isNaN(eb, mb)
        e.op(Add)
    }) { fc.pushNaN() }
    returnIf("infs", {
        // inf + -inf
        fc.isInf(ea, ma)
        fc.isInf(eb, mb)
        e.op(Multiply)
        sa.get()
        sb.get()
        e.op(Subtract)
        e.op(Multiply)
    }) { fc.pushNaN() }
    returnIf("ainf", { fc.isInf(ea, ma) }) { a.get() }
    returnIf("binf", { fc.isInf(eb, mb) }) { b.get() }
    fc.implicit(ea, ma, ea)
    fc.implicit(eb, mb, eb)
    ma.set {
        ma.get()
        e.const(8)
        e.op(Multiply)
    }
    mb.set {
        mb.get()
        e.const(8)
        e.op(Multiply)
    }
    // Make a the larger in magnitude: swap when eb > ea, or equal and mb > ma.
    t.set {
        eb.get()
        ea.get()
        e.op(Greater)
        eb.get()
        ea.get()
        e.op(Subtract)
        e.op(Not)
        mb.get()
        ma.get()
        e.op(Greater)
        e.op(Multiply)
        e.op(Add)
    }
    e.ifThen(label("swap").hint, { t.get() }) {
        m.set { ma.get() }
        ma.set { mb.get() }
        mb.set { m.get() }
        t.set { ea.get() }
        ea.set { eb.get() }
        eb.set { t.get() }
        t.set { sa.get() }
        sa.set { sb.get() }
        sb.set { t.get() }
    }
    mb.set {
        mb.get()
        e.shiftRightSticky {
            ea.get()
            eb.get()
            e.op(Subtract)
            e.const(62)
            e.minTop()
        }
    }
    e.ifElse(label("same").hint, {
        sa.get()
        sb.get()
        e.op(Subtract)
        e.op(Not)
    }, {
        m.set {
            ma.get()
            mb.get()
            e.op(Add)
        }
        t.set {
            m.get()
            e.const((1L shl 56) - 1)
            e.op(Greater)
        }
        m.set {
            m.get()
            e.shiftRightSticky { t.get() }
        }
        ea.set {
            ea.get()
            t.get()
            e.op(Add)
        }
    }) {
        m.set {
            ma.get()
            mb.get()
            e.op(Subtract)
        }
        returnIf("exact0", {
            m.get()
            e.op(Not)
        }) {
            e.const(0)
            frame.pack64()
        }
        fc.normalize(m, ea, 55)
    }
    fc.roundPack(sa, ea, m)
    ret(true)
}

/** a * b */
val Runtime.floatMul: Label get() = routine("f64_mul", 2) {
    val L = Locals(this)
    val a = L.V(param(0))
    val b = L.V(param(1))
    val sa = L.v()
    val ea = L.v()
    val sb = L.v()
    val eb = L.v()
    val s = L.v()
    val t = L.v()
    val ma = L.wide()
    val mb = L.wide()
    val m = L.wide()
    val bits = L.wide()
    val x = L.wide()
    val y = L.wide()
    val z = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, sa, ea, ma, bits)
    fc.unpack(b, sb, eb, mb, bits)
    s.set {
        sa.get()
        sb.get()
        e.op(Add)
        e.const(2)
        e.op(Mod)
    }
    returnIf("nan", {
        fc.isNaN(ea, ma)
        fc.isNaN(eb, mb)
        e.op(Add)
        // inf * 0
        fc.isInf(ea, ma)
        fc.isZero(eb, mb)
        e.op(Multiply)
        e.op(Add)
        fc.isZero(ea, ma)
        fc.isInf(eb, mb)
        e.op(Multiply)
        e.op(Add)
    }) { fc.pushNaN() }
    returnIf("inf", {
        fc.isInf(ea, ma)
        fc.isInf(eb, mb)
        e.op(Add)
    }) { fc.pushSignedInfinity(s) }
    returnIf("zero", {
        fc.isZero(ea, ma)
        fc.isZero(eb, mb)
        e.op(Add)
    }) { fc.pushSignedZero(s) }
    fc.implicit(ea, ma, ea)
    fc.implicit(eb, mb, eb)
    fc.normalize(ma, ea, 52)
    fc.normalize(mb, eb, 52)
    // P = A * 2^52 + B * 2^26 + C, from 26-bit halves; m = P >> 49 with sticky.
    x.set {
        // A = ah * bh
        ma.get()
        e.const(1L shl 26)
        e.op(Divide)
        mb.get()
        e.const(1L shl 26)
        e.op(Divide)
        e.op(Multiply)
    }
    y.set {
        // B = ah * bl + al * bh
        ma.get()
        e.const(1L shl 26)
        e.op(Divide)
        mb.get()
        e.const(1L shl 26)
        e.op(Mod)
        e.op(Multiply)
        ma.get()
        e.const(1L shl 26)
        e.op(Mod)
        mb.get()
        e.const(1L shl 26)
        e.op(Divide)
        e.op(Multiply)
        e.op(Add)
    }
    z.set {
        // T = (B mod 2^23) * 2^26 + al * bl
        y.get()
        e.const(1L shl 23)
        e.op(Mod)
        e.const(1L shl 26)
        e.op(Multiply)
        ma.get()
        e.const(1L shl 26)
        e.op(Mod)
        mb.get()
        e.const(1L shl 26)
        e.op(Mod)
        e.op(Multiply)
        e.op(Add)
    }
    m.set {
        // A * 8 + B / 2^23 + T / 2^49
        x.get()
        e.const(8)
        e.op(Multiply)
        y.get()
        e.const(1L shl 23)
        e.op(Divide)
        e.op(Add)
        z.get()
        e.const(1L shl 49)
        e.op(Divide)
        e.op(Add)
    }
    t.set {
        z.get()
        e.const(1L shl 49)
        e.op(Mod)
        e.op(Not)
        e.op(Not)
    }
    m.set {
        // OR the sticky bit into bit 0
        m.get()
        t.get()
        m.get()
        e.const(2)
        e.op(Mod)
        e.op(Not)
        e.op(Multiply)
        e.op(Add)
    }
    ea.set {
        ea.get()
        eb.get()
        e.op(Add)
        e.const(1023)
        e.op(Subtract)
    }
    t.set {
        m.get()
        e.const((1L shl 56) - 1)
        e.op(Greater)
    }
    m.set {
        m.get()
        e.shiftRightSticky { t.get() }
    }
    ea.set {
        ea.get()
        t.get()
        e.op(Add)
    }
    fc.roundPack(s, ea, m)
    ret(true)
}

/** a / b, by long division ten bits at a time. */
val Runtime.floatDiv: Label get() = routine("f64_div", 2) {
    val L = Locals(this)
    val a = L.V(param(0))
    val b = L.V(param(1))
    val sa = L.v()
    val ea = L.v()
    val sb = L.v()
    val eb = L.v()
    val s = L.v()
    val t = L.v()
    val ma = L.wide()
    val mb = L.wide()
    val q = L.wide()
    val r = L.wide()
    val bits = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, sa, ea, ma, bits)
    fc.unpack(b, sb, eb, mb, bits)
    s.set {
        sa.get()
        sb.get()
        e.op(Add)
        e.const(2)
        e.op(Mod)
    }
    returnIf("nan", {
        fc.isNaN(ea, ma)
        fc.isNaN(eb, mb)
        e.op(Add)
        fc.isInf(ea, ma)
        fc.isInf(eb, mb)
        e.op(Multiply)
        e.op(Add)
        fc.isZero(ea, ma)
        fc.isZero(eb, mb)
        e.op(Multiply)
        e.op(Add)
    }) { fc.pushNaN() }
    returnIf("inf", {
        fc.isInf(ea, ma)
        fc.isZero(eb, mb)
        e.op(Add)
    }) { fc.pushSignedInfinity(s) }
    returnIf("zero", {
        fc.isZero(ea, ma)
        fc.isInf(eb, mb)
        e.op(Add)
    }) { fc.pushSignedZero(s) }
    fc.implicit(ea, ma, ea)
    fc.implicit(eb, mb, eb)
    fc.normalize(ma, ea, 52)
    fc.normalize(mb, eb, 52)
    ea.set {
        ea.get()
        eb.get()
        e.op(Subtract)
        e.const(1023)
        e.op(Add)
    }
    // 1 <= ma / mb < 2
    t.set {
        mb.get()
        ma.get()
        e.op(Greater)
    }
    ma.set {
        ma.get()
        t.get()
        e.const(1)
        e.op(Add)
        e.op(Multiply)
    }
    ea.set {
        ea.get()
        t.get()
        e.op(Subtract)
    }
    q.set { e.const(1) }
    r.set {
        ma.get()
        mb.get()
        e.op(Subtract)
    }
    for (bitsHere in intArrayOf(10, 10, 10, 10, 10, 5)) {
        r.set {
            r.get()
            e.const(1L shl bitsHere)
            e.op(Multiply)
        }
        q.set {
            q.get()
            e.const(1L shl bitsHere)
            e.op(Multiply)
            r.get()
            mb.get()
            e.op(Divide)
            e.op(Add)
        }
        r.set {
            r.get()
            mb.get()
            e.op(Mod)
        }
    }
    q.set {
        q.get()
        r.get()
        e.op(Not)
        e.op(Not)
        q.get()
        e.const(2)
        e.op(Mod)
        e.op(Not)
        e.op(Multiply)
        e.op(Add)
    }
    fc.roundPack(s, ea, q)
    ret(true)
}

/**
 * The total order Temper compares Float64s by: -0.0 below 0.0, and NaN
 * equal to itself and above everything. `[box] -> [key]`, a wide integer
 * whose order is that order: for negative bits, -1 - (bits - MIN).
 */
val Runtime.floatKey: Label get() = routine("f64_key", 1) {
    val L = Locals(this)
    val a = L.V(param(0))
    val s = L.v()
    val ef = L.v()
    val frac = L.wide()
    val bits = L.wide()
    val k = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, s, ef, frac, bits)
    returnIf("nan", { fc.isNaN(ef, frac) }) {
        e.const(CANONICAL_NAN)
        frame.pack64()
    }
    k.set {
        e.ifElseValueWideSafe(s, {
            e.const(-1)
            bits.get()
            e.constMin64()
            e.op(Subtract)
            e.op(Subtract)
        }) { bits.get() }
    }
    k.get()
    frame.pack64()
    ret(true)
}

/** Pushes one of two wide values chosen by the 0/1 in [cond], computing both. No branch. */
private fun Emitter.ifElseValueWideSafe(cond: Locals.V, ifTrue: () -> Unit, ifFalse: () -> Unit) {
    // c * x + (1 - c) * y, each product of a 0/1 and a value.
    ifTrue()
    cond.get()
    op(Multiply)
    ifFalse()
    cond.get()
    op(Not)
    op(Multiply)
    op(Add)
}

/** -1, 0 or 1 in Temper's total order. */
val Runtime.floatCompare: Label get() {
    val key = floatKey
    return routine("f64_cmp", 2) {
        val L = Locals(this)
        val a = L.V(param(0))
        val b = L.V(param(1))
        val ka = L.wide()
        val kb = L.wide()
        L.begin()
        ka.set {
            call(frame, key, 1, true) { a.get() }
            frame.unpack64()
        }
        kb.set {
            call(frame, key, 1, true) { b.get() }
            frame.unpack64()
        }
        ka.get()
        kb.get()
        e.op(Greater)
        kb.get()
        ka.get()
        e.op(Greater)
        e.op(Subtract)
        ret(true)
    }
}

/** An Int32 as a Float64, exactly. */
val Runtime.floatFromInt: Label get() = routine("f64_from_i32", 1) {
    val L = Locals(this)
    val n = L.V(param(0))
    val s = L.v()
    val ex = L.v()
    val m = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    returnIf("zero", {
        n.get()
        e.op(Not)
    }) {
        e.const(0)
        frame.pack64()
    }
    s.set {
        e.const(0)
        n.get()
        e.op(Greater)
    }
    m.set {
        n.get()
        s.get()
        e.const(-2)
        e.op(Multiply)
        e.const(1)
        e.op(Add)
        e.op(Multiply)
    }
    ex.set { e.const(1023 + 55) }
    fc.normalize(m, ex, 55)
    fc.roundPack(s, ex, m)
    ret(true)
}

/** An Int64 as a Float64, rounded. */
val Runtime.floatFromInt64: Label get() = routine("f64_from_i64", 1) {
    val L = Locals(this)
    val n = L.V(param(0))
    val s = L.v()
    val ex = L.v()
    val t = L.v()
    val m = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    m.set {
        n.get()
        frame.unpack64()
    }
    returnIf("zero", {
        m.get()
        e.op(Not)
    }) {
        e.const(0)
        frame.pack64()
    }
    // MIN has no positive counterpart, and is exactly -2^63.
    returnIf("min", {
        m.get()
        e.constMin64()
        e.op(Subtract)
        e.op(Not)
    }) {
        e.const(0xC3E0000000000000UL.toLong())
        frame.pack64()
    }
    s.set {
        e.const(0)
        m.get()
        e.op(Greater)
    }
    m.set {
        m.get()
        s.get()
        e.const(-2)
        e.op(Multiply)
        e.const(1)
        e.op(Add)
        e.op(Multiply)
    }
    ex.set { e.const(1023 + 55) }
    // Up to 63 bits: bring it under 2^56 first, sticky, then normalize.
    for (sh in intArrayOf(4, 2, 1)) {
        t.set {
            m.get()
            e.const((1L shl (56 + sh - 1)) - 1)
            e.op(Greater)
        }
        m.set {
            m.get()
            e.shiftRightSticky {
                t.get()
                e.const(sh)
                e.op(Multiply)
            }
        }
        ex.set {
            ex.get()
            t.get()
            e.const(sh)
            e.op(Multiply)
            e.op(Add)
        }
    }
    t.set {
        m.get()
        e.const((1L shl 56) - 1)
        e.op(Greater)
    }
    m.set {
        m.get()
        e.shiftRightSticky { t.get() }
    }
    ex.set {
        ex.get()
        t.get()
        e.op(Add)
    }
    fc.normalize(m, ex, 55)
    fc.roundPack(s, ex, m)
    ret(true)
}

/**
 * Truncates toward zero to an Int64 box. With [checkLimit], bubbles for NaN,
 * infinities and magnitudes past [limit]; without, returns a value for them
 * that is merely some value.
 */
fun Runtime.floatToInt64(checkLimit: Boolean, limit: Long): Label =
    routine("f64_to_i64_${if (checkLimit) "checked_$limit" else "unsafe"}", 1) {
        val L = Locals(this)
        val a = L.V(param(0))
        val s = L.v()
        val ef = L.v()
        val sh = L.v()
        val frac = L.wide()
        val bits = L.wide()
        val m = L.wide()
        val fc = FloatCode(this, L)
        L.begin()
        fc.unpack(a, s, ef, frac, bits)
        if (checkLimit) {
            val bad = failLanding()
            val ok = label("finite")
            ef.get()
            e.const(F_INF_EXP)
            e.op(Subtract)
            e.branch(ok, bad)
            e.startBlock(ok)
        } else {
            returnIf("special", {
                ef.get()
                e.const(F_INF_EXP)
                e.op(Subtract)
                e.op(Not)
            }) {
                e.const(0)
                frame.pack64()
            }
        }
        m.set { frac.get() }
        fc.implicit(ef, m, ef)
        // value = m * 2^(ef - 1075)
        returnIf("tiny", {
            e.const(1023)
            ef.get()
            e.op(Greater)
        }) {
            e.const(0)
            frame.pack64()
        }
        // ef >= 1023 here. Shifting left past 1086 overflows 64 bits.
        if (checkLimit) {
            val ok = label("small")
            e.const(1023 + 63)
            ef.get()
            e.op(Greater)
            e.branch(ok, failLanding(e.depth - 1))
            e.startBlock(ok)
        }
        sh.set {
            ef.get()
            e.const(1075)
            e.op(Subtract)
        }
        e.ifElse(label("left").hint, {
            sh.get()
            e.const(0)
            e.op(Greater)
        }, {
            m.set {
                m.get()
                sh.get()
                e.const(63)
                e.minTop()
                e.pow2()
                e.op(Multiply)
            }
        }) {
            m.set {
                m.get()
                e.const(0)
                sh.get()
                e.op(Subtract)
                e.pow2()
                e.op(Divide)
            }
        }
        if (checkLimit) {
            val ok = label("within")
            e.const(limit + 1)
            m.get()
            e.op(Greater)
            e.branch(ok, failLanding(e.depth - 1))
            e.startBlock(ok)
        }
        m.get()
        s.get()
        e.const(-2)
        e.op(Multiply)
        e.const(1)
        e.op(Add)
        e.op(Multiply)
        frame.pack64()
        ret(true)
        end()
    }

/** Flips the sign bit. */
val Runtime.floatNegate: Label get() = routine("f64_neg", 1) {
    val L = Locals(this)
    val a = L.V(param(0))
    val bits = L.wide()
    L.begin()
    bits.set {
        a.get()
        frame.unpack64()
    }
    // bits < 0 ? bits - MIN : bits + MIN, neither of which overflows.
    bits.get()
    e.constMin64()
    e.const(0)
    bits.get()
    e.op(Greater)
    e.const(-2)
    e.op(Multiply)
    e.const(1)
    e.op(Add)
    e.op(Multiply)
    e.op(Add)
    frame.pack64()
    ret(true)
}

/** Clears the sign bit. */
val Runtime.floatAbs: Label get() = routine("f64_abs", 1) {
    val L = Locals(this)
    val a = L.V(param(0))
    val bits = L.wide()
    L.begin()
    bits.set {
        a.get()
        frame.unpack64()
    }
    bits.get()
    e.constMin64()
    e.const(0)
    bits.get()
    e.op(Greater)
    e.op(Multiply)
    e.op(Subtract)
    frame.pack64()
    ret(true)
}
