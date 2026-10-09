package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Subtract

/*
 * The rest of Float64. sqrt, the roundings, the remainder and the
 * conversions to integers are exact, on the bits. The transcendental
 * functions are built from the correctly rounded operations: range
 * reduction and a series, good to within a few ulps, which is what Temper's
 * own tests ask of them (`near`, relative tolerance 1e-9).
 */

private const val FRAC = 1L shl 52

/** Calls on boxes, for routines written in terms of other float routines. */
class FloatOps(private val w: FunctionWriter, private val rt: Runtime) {
    private val e = w.e
    private val f = w.frame

    fun c(d: Double) {
        e.const(d.toRawBits())
        f.pack64()
    }

    private fun call2(target: Label, a: () -> Unit, b: () -> Unit) = call(f, target, 2, true) {
        a()
        b()
    }

    fun add(a: () -> Unit, b: () -> Unit) = call2(rt.floatAdd, a, b)
    fun sub(a: () -> Unit, b: () -> Unit) = call2(rt.floatAdd, a) { neg(b) }
    fun mul(a: () -> Unit, b: () -> Unit) = call2(rt.floatMul, a, b)
    fun div(a: () -> Unit, b: () -> Unit) = call2(rt.floatDiv, a, b)
    fun neg(a: () -> Unit) = call(f, rt.floatNegate, 1, true) { a() }
    fun abs(a: () -> Unit) = call(f, rt.floatAbs, 1, true) { a() }

    /** Pushes -1, 0 or 1 in the total order. */
    fun cmp(a: () -> Unit, b: () -> Unit) = call2(rt.floatCompare, a, b)

    /** Pushes whether a < b. */
    fun lt(a: () -> Unit, b: () -> Unit) {
        cmp(a, b)
        e.const(0)
        e.swap()
        e.op(Greater)
    }

    fun one(name: Label, a: () -> Unit) = call(f, name, 1, true) { a() }
}

/** Unpacked fields of a float in [box]. */
private fun FloatCode.fields(box: Locals.V, s: Locals.V, ef: Locals.V, frac: Locals.Wide, bits: Locals.Wide) =
    unpack(box, s, ef, frac, bits)

/** Pushes whether a box holds NaN. */
fun Runtime.isNaNCall(frame: Frame, push: () -> Unit) {
    // NaN is the only value whose key is the canonical NaN's bits... compare with itself is total, so check fields.
    call(frame, floatIsNaN, 1, true) { push() }
}

val Runtime.floatIsNaN: Label get() = routine("f64_isnan", 1) {
    val L = Locals(this)
    val a = L.V(param(0))
    val s = L.v()
    val ef = L.v()
    val frac = L.wide()
    val bits = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, s, ef, frac, bits)
    fc.isNaN(ef, frac)
    ret(true)
}

/** Rounds toward zero to an integral Float64, exactly. */
val Runtime.floatTrunc: Label get() = routine("f64_trunc", 1) {
    val L = Locals(this)
    val a = L.V(param(0))
    val s = L.v()
    val ef = L.v()
    val frac = L.wide()
    val bits = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, s, ef, frac, bits)
    // Already integral (or not finite): ef >= 1075.
    val keep = label("keep")
    val cut = label("cut")
    ef.get()
    e.const(1074)
    e.op(Greater)
    e.branch(keep, cut)
    e.startBlock(keep)
    a.get()
    ret(true)
    e.startBlock(cut)
    // Below 1: a signed zero.
    val zero = label("zero")
    val some = label("some")
    e.const(1023)
    ef.get()
    e.op(Greater)
    e.branch(zero, some)
    e.startBlock(zero)
    fc.pushSignedZero(s)
    ret(true)
    e.startBlock(some)
    // Clear the fraction bits below the binary point: 1075 - ef of them.
    frac.set {
        frac.get()
        frac.get()
        e.const(1075)
        ef.get()
        e.op(Subtract)
        e.pow2()
        e.op(Mod)
        e.op(Subtract)
    }
    fc.packBits(s, ef, frac)
    ret(true)
}

/** floor, ceil and round from trunc: an adjustment of one when a fraction was dropped. */
fun Runtime.floatRounding(kind: String): Label {
    val rt = this
    return routine("f64_$kind", 1) {
        val L = Locals(this)
        val a = L.V(param(0))
        val t = L.v()
        val d = L.v()
        val ops = FloatOps(this, rt)
        L.begin()
        e.ifThen(label("nan").hint, { rt.isNaNCall(frame) { a.get() } }) {
            a.get()
            ret(true)
        }
        when (kind) {
            "floor", "ceil" -> {
                t.set { ops.one(rt.floatTrunc) { a.get() } }
                // floor: one less when a < t; ceil: one more when a > t.
                e.ifThen(label("adjust").hint, {
                    if (kind == "floor") ops.lt({ a.get() }, { t.get() }) else ops.lt({ t.get() }, { a.get() })
                }) {
                    t.set { ops.add({ t.get() }) { ops.c(if (kind == "floor") -1.0 else 1.0) } }
                }
                t.get()
            }
            else -> {
                // round half toward +infinity, as JavaScript's Math.round: floor(a), plus
                // one when a - floor(a) >= 0.5. a - floor(a) is exact.
                t.set { ops.one(rt.floatRounding("floor")) { a.get() } }
                d.set { ops.sub({ a.get() }, { t.get() }) }
                e.ifThen(label("up").hint, {
                    ops.lt({ d.get() }, { ops.c(0.5) })
                    e.op(Not)
                }) {
                    t.set { ops.add({ t.get() }) { ops.c(1.0) } }
                }
                t.get()
            }
        }
        ret(true)
    }
}

/** sqrt, correctly rounded: an integer square root of the significand, two bits at a time, unrolled. */
val Runtime.floatSqrt: Label get() = routine("f64_sqrt", 1) {
    val L = Locals(this)
    val a = L.V(param(0))
    val s = L.v()
    val ef = L.v()
    val ex = L.v()
    val frac = L.wide()
    val bits = L.wide()
    val m = L.wide()
    val rem = L.wide()
    val root = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, s, ef, frac, bits)
    fun returnIf(name: String, cond: () -> Unit, push: () -> Unit) {
        val yes = label("$name.yes")
        val no = label("$name.no")
        cond()
        val d0 = e.depth - 1
        e.branch(yes, no)
        e.startBlock(yes, d0)
        push()
        ret(true)
        e.startBlock(no, d0)
    }
    returnIf("nan", { fc.isNaN(ef, frac) }) { fc.pushNaN() }
    returnIf("zero", { fc.isZero(ef, frac) }) { a.get() }
    returnIf("neg", { s.get() }) { fc.pushNaN() }
    returnIf("inf", { fc.isInf(ef, frac) }) { a.get() }
    m.set { frac.get() }
    fc.implicit(ef, m, ex)
    fc.normalize(m, ex, 52)
    // x = m * 2^(ex - 1075); make ex - 1075 even by doubling m.
    val odd = L.V(s.slot) // s is 0 here; reuse its slot as the parity flag
    odd.set {
        ex.get()
        e.const(1075)
        e.op(Subtract)
        e.const(2)
        e.op(Mod)
        e.op(Not)
        e.op(Not)
    }
    m.set {
        m.get()
        odd.get()
        e.const(1)
        e.op(Add)
        e.op(Multiply)
    }
    ex.set {
        ex.get()
        odd.get()
        e.op(Subtract)
    }
    // root = isqrt(m * 2^58): 56 result bits, from m's 27 bit pairs then zeros.
    rem.set { e.const(0) }
    root.set { e.const(0) }
    for (i in 0 until 56) {
        rem.set {
            rem.get()
            e.const(4)
            e.op(Multiply)
            if (i < 27) {
                m.get()
                e.const(1L shl (2 * (26 - i)))
                e.op(Divide)
                e.const(4)
                e.op(Mod)
                e.op(Add)
            }
        }
        // t = root * 4 + 1; if rem >= t: rem -= t, root = root * 2 + 1, else root *= 2
        val take = L.V(odd.slot)
        take.set {
            rem.get()
            root.get()
            e.const(4)
            e.op(Multiply)
            e.const(1)
            e.op(Add)
            // [rem, t] -> !(t > rem)
            e.op(Greater)
            // that is rem > t; also equal counts
            rem.get()
            root.get()
            e.const(4)
            e.op(Multiply)
            e.const(1)
            e.op(Add)
            e.op(Subtract)
            e.op(Not)
            e.op(Add)
        }
        rem.set {
            rem.get()
            take.get()
            root.get()
            e.const(4)
            e.op(Multiply)
            e.const(1)
            e.op(Add)
            e.op(Multiply)
            e.op(Subtract)
        }
        root.set {
            root.get()
            e.const(2)
            e.op(Multiply)
            take.get()
            e.op(Add)
        }
    }
    // sticky, and the exponent: sqrt(x) = root * 2^((ex - 1075) / 2 - 29)
    root.set {
        root.get()
        rem.get()
        e.op(Not)
        e.op(Not)
        root.get()
        e.const(2)
        e.op(Mod)
        e.op(Not)
        e.op(Multiply)
        e.op(Add)
    }
    ex.set {
        ex.get()
        e.const(1075)
        e.op(Subtract)
        e.const(2)
        e.op(Divide)
        e.const(1049)
        e.op(Add)
    }
    val zero = L.V(odd.slot)
    zero.set { e.const(0) }
    fc.roundPack(zero, ex, root)
    ret(true)
}

/** a % b with the sign of a, exactly, as C's fmod. */
val Runtime.floatRem: Label get() = routine("f64_rem", 2) {
    val L = Locals(this)
    val a = L.V(param(0))
    val b = L.V(param(1))
    val sa = L.v()
    val ea = L.v()
    val sb = L.v()
    val eb = L.v()
    val d = L.v()
    val ma = L.wide()
    val mb = L.wide()
    val bits = L.wide()
    val fc = FloatCode(this, L)
    L.begin()
    fc.unpack(a, sa, ea, ma, bits)
    fc.unpack(b, sb, eb, mb, bits)
    fun returnIf(name: String, cond: () -> Unit, push: () -> Unit) {
        val yes = label("$name.yes")
        val no = label("$name.no")
        cond()
        val d0 = e.depth - 1
        e.branch(yes, no)
        e.startBlock(yes, d0)
        push()
        ret(true)
        e.startBlock(no, d0)
    }
    returnIf("nan", {
        fc.isNaN(ea, ma)
        fc.isNaN(eb, mb)
        e.op(Add)
        fc.isInf(ea, ma)
        e.op(Add)
        fc.isZero(eb, mb)
        e.op(Add)
    }) { fc.pushNaN() }
    returnIf("binf", { fc.isInf(eb, mb) }) { a.get() }
    returnIf("azero", { fc.isZero(ea, ma) }) { a.get() }
    fc.implicit(ea, ma, ea)
    fc.implicit(eb, mb, eb)
    fc.normalize(ma, ea, 52)
    fc.normalize(mb, eb, 52)
    // |a| < |b|: a
    returnIf("small", {
        eb.get()
        ea.get()
        e.op(Greater)
    }) { a.get() }
    // r = ma * 2^(ea - eb) mod mb, ten bits at a time.
    ma.set {
        ma.get()
        mb.get()
        e.op(Mod)
    }
    e.whileLoop(label("reduce").hint, {
        ea.get()
        eb.get()
        e.op(Greater)
    }) {
        d.set {
            ea.get()
            eb.get()
            e.op(Subtract)
            e.const(10)
            e.minTop()
        }
        ma.set {
            ma.get()
            d.get()
            e.pow2()
            e.op(Multiply)
            mb.get()
            e.op(Mod)
        }
        ea.set {
            ea.get()
            d.get()
            e.op(Subtract)
        }
    }
    returnIf("exact", {
        ma.get()
        e.op(Not)
    }) { fc.pushSignedZero(sa) }
    // value = ma * 2^(eb - 1075): leading bit to 2^55 with exponent eb + 3 - 3.
    ma.set {
        ma.get()
        e.const(8)
        e.op(Multiply)
    }
    fc.normalize(ma, eb, 55)
    fc.roundPack(sa, eb, ma)
    ret(true)
}

/**
 * Truncates toward zero to an Int64 box. [checked] bubbles outside
 * +/-(2^53 - 1) and for NaN; unchecked saturates and gives 0 for NaN, as
 * be-js does.
 */
fun Runtime.floatToInt64(checked: Boolean): Label = routine("f64_to_i64_${if (checked) "checked" else "sat"}", 1) {
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
    fun returnIf(name: String, cond: () -> Unit, push: () -> Unit) {
        val yes = label("$name.yes")
        val no = label("$name.no")
        cond()
        val d0 = e.depth - 1
        e.branch(yes, no)
        e.startBlock(yes, d0)
        push()
        ret(true)
        e.startBlock(no, d0)
    }
    if (checked) {
        // 2^53 and up, infinities and NaN all have ef >= 1076.
        val ok = label("safe")
        e.const(1076)
        ef.get()
        e.op(Greater)
        e.branch(ok, failLanding(e.depth - 1))
        e.startBlock(ok)
    } else {
        returnIf("nan", { fc.isNaN(ef, frac) }) {
            e.const(0)
            frame.pack64()
        }
        returnIf("big", {
            ef.get()
            e.const(1085)
            e.op(Greater)
        }) {
            e.ifElseValue(label("sat").hint, { s.get() }, { e.constMin64() }) { e.const(Long.MAX_VALUE) }
            frame.pack64()
        }
    }
    returnIf("small", {
        e.const(1023)
        ef.get()
        e.op(Greater)
    }) {
        e.const(0)
        frame.pack64()
    }
    m.set { frac.get() }
    fc.implicit(ef, m, ef)
    sh.set {
        ef.get()
        e.const(1075)
        e.op(Subtract)
    }
    e.ifElse(label("left").hint, {
        sh.get()
        e.const(-1)
        e.op(Greater)
    }, {
        m.set {
            m.get()
            sh.get()
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

/** 2^k as a box, k from -1022 to 1023. */
private fun FunctionWriter.pushPow2Float(k: Locals.V) {
    k.get()
    e.const(1023)
    e.op(Add)
    e.const(FRAC)
    e.op(Multiply)
    frame.pack64()
}

/** x * 2^k for any k, in two steps so each factor is a normal power of two. */
val Runtime.floatLdexp: Label get() {
    val rt = this
    return routine("f64_ldexp", 2) {
        val L = Locals(this)
        val x = L.V(param(0))
        val k = L.V(param(1))
        val k1 = L.v()
        val ops = FloatOps(this, rt)
        L.begin()
        k.set {
            k.get()
            e.const(-2000)
            e.maxTop()
            e.const(2000)
            e.minTop()
        }
        k1.set {
            k.get()
            e.const(2)
            e.op(Divide)
        }
        k.set {
            k.get()
            k1.get()
            e.op(Subtract)
        }
        x.set { ops.mul({ x.get() }, { pushPow2Float(k1) }) }
        ops.mul({ x.get() }, { pushPow2Float(k) })
        ret(true)
    }
}

/** Horner's rule over [coefficients], highest power first: c0*t^n + ... + cn. */
private fun FunctionWriter.horner(ops: FloatOps, t: Locals.V, acc: Locals.V, coefficients: List<Double>) {
    acc.set { ops.c(coefficients.first()) }
    for (c in coefficients.drop(1)) {
        acc.set { ops.add({ ops.mul({ acc.get() }, { t.get() }) }, { ops.c(c) }) }
    }
}

private fun factorialSeries(terms: Int, start: Int, step: Int, alternate: Boolean): List<Double> {
    // Coefficients of t^0..t^(terms-1) where t is x^step and term i is +-1/(start + step*i)!
    val out = mutableListOf<Double>()
    for (i in 0 until terms) {
        var f = 1.0
        for (j in 2..(start + step * i)) f *= j
        out += (if (alternate && i % 2 == 1) -1.0 else 1.0) / f
    }
    return out.reversed()
}

private const val LN2_HI = 6.93147180369123816490e-01
private const val LN2_LO = 1.90821492927058770002e-10
private const val PIO2_HI = 1.57079632673412561417e+00
private const val PIO2_LO = 6.07710050650619224932e-11

/** e^x: x = k ln2 + r with |r| <= ln2 / 2, a series for e^r, then 2^k. */
val Runtime.floatExp: Label get() {
    val rt = this
    return routine("f64_exp", 1) {
        val L = Locals(this)
        val x = L.V(param(0))
        val k = L.v()
        val kf = L.v()
        val r = L.v()
        val acc = L.v()
        val ops = FloatOps(this, rt)
        L.begin()
        e.ifThen(label("nan").hint, { rt.isNaNCall(frame) { x.get() } }) {
            x.get()
            ret(true)
        }
        e.ifThen(label("over").hint, { ops.lt({ ops.c(709.8) }, { x.get() }) }) {
            ops.c(Double.POSITIVE_INFINITY)
            ret(true)
        }
        e.ifThen(label("under").hint, { ops.lt({ x.get() }, { ops.c(-745.2) }) }) {
            ops.c(0.0)
            ret(true)
        }
        // k = round(x / ln2)
        kf.set { ops.one(rt.floatRounding("round")) { ops.div({ x.get() }, { ops.c(LN2_HI + LN2_LO) }) } }
        k.set { call(frame, rt.floatToInt64(false), 1, true) { kf.get() }; frame.unpack64() }
        r.set {
            ops.sub({ ops.sub({ x.get() }, { ops.mul({ kf.get() }, { ops.c(LN2_HI) }) }) }) {
                ops.mul({ kf.get() }, { ops.c(LN2_LO) })
            }
        }
        // e^r = sum r^i / i!, i = 0..16
        horner(ops, r, acc, factorialSeries(17, 0, 1, false))
        call(frame, rt.floatLdexp, 2, true) {
            acc.get()
            k.get()
        }
        ret(true)
    }
}

/** log x: x = m 2^k with m in [sqrt(1/2), sqrt(2)), then 2 atanh((m-1)/(m+1)). */
val Runtime.floatLog: Label get() {
    val rt = this
    return routine("f64_log", 1) {
        val L = Locals(this)
        val x = L.V(param(0))
        val s = L.v()
        val ef = L.v()
        val k = L.v()
        val m = L.v()
        val t = L.v()
        val z = L.v()
        val acc = L.v()
        val frac = L.wide()
        val bits = L.wide()
        val fc = FloatCode(this, L)
        val ops = FloatOps(this, rt)
        L.begin()
        fc.unpack(x, s, ef, frac, bits)
        e.ifThen(label("nan").hint, {
            fc.isNaN(ef, frac)
            // negative non-zero
            s.get()
            fc.isZero(ef, frac)
            e.op(Not)
            e.op(Multiply)
            e.op(Add)
        }) {
            fc.pushNaN()
            ret(true)
        }
        e.ifThen(label("zero").hint, { fc.isZero(ef, frac) }) {
            ops.c(Double.NEGATIVE_INFINITY)
            ret(true)
        }
        e.ifThen(label("inf").hint, { fc.isInf(ef, frac) }) {
            x.get()
            ret(true)
        }
        // Subnormal: scale up by 2^54 first.
        k.set { e.const(0) }
        e.ifThen(label("sub").hint, {
            ef.get()
            e.op(Not)
        }) {
            x.set { ops.mul({ x.get() }, { ops.c(18014398509481984.0) }) }
            k.set { e.const(-54) }
            fc.unpack(x, s, ef, frac, bits)
        }
        k.set {
            k.get()
            ef.get()
            e.op(Add)
            e.const(1023)
            e.op(Subtract)
        }
        // m in [1, 2)
        m.set {
            e.const(1023L * FRAC)
            frac.get()
            e.op(Add)
            frame.pack64()
        }
        e.ifThen(label("half").hint, { ops.lt({ ops.c(1.4142135623730951) }, { m.get() }) }) {
            m.set { ops.mul({ m.get() }, { ops.c(0.5) }) }
            k.set {
                k.get()
                e.const(1)
                e.op(Add)
            }
        }
        // z = (m - 1) / (m + 1); log m = 2 z (1 + z^2/3 + z^4/5 + ...)
        z.set { ops.div({ ops.sub({ m.get() }, { ops.c(1.0) }) }, { ops.add({ m.get() }, { ops.c(1.0) }) }) }
        t.set { ops.mul({ z.get() }, { z.get() }) }
        horner(ops, t, acc, (0 until 14).map { 1.0 / (2 * it + 1) }.reversed())
        acc.set { ops.mul({ ops.mul({ acc.get() }, { z.get() }) }, { ops.c(2.0) }) }
        t.set { call(frame, rt.floatFromInt, 1, true) { k.get() } }
        ops.add({ ops.add({ ops.mul({ t.get() }, { ops.c(LN2_LO) }) }, { acc.get() }) }) {
            ops.mul({ t.get() }, { ops.c(LN2_HI) })
        }
        ret(true)
    }
}

/**
 * sin or cos: x = k pi/2 + r with |r| <= pi/4, series for sin r and cos r,
 * then the one k mod 4 calls for, with its sign.
 */
fun Runtime.floatTrig(cosine: Boolean): Label {
    val rt = this
    return routine(if (cosine) "f64_cos" else "f64_sin", 1) {
        val L = Locals(this)
        val x = L.V(param(0))
        val k = L.v()
        val kf = L.v()
        val r = L.v()
        val t = L.v()
        val acc = L.v()
        val sr = L.v()
        val cr = L.v()
        val q = L.v()
        val ops = FloatOps(this, rt)
        L.begin()
        e.ifThen(label("nan").hint, {
            rt.isNaNCall(frame) { ops.sub({ x.get() }, { x.get() }) }
        }) {
            // NaN or infinite
            ops.c(Double.NaN)
            ret(true)
        }
        kf.set {
            ops.one(rt.floatRounding("round")) { ops.div({ x.get() }, { ops.c(PIO2_HI + PIO2_LO) }) }
        }
        k.set {
            call(frame, rt.floatToInt64(false), 1, true) { kf.get() }
            frame.unpack64()
            e.const(4)
            e.op(Mod)
            e.const(4)
            e.op(Add)
            e.const(4)
            e.op(Mod)
        }
        r.set {
            ops.sub({ ops.sub({ x.get() }, { ops.mul({ kf.get() }, { ops.c(PIO2_HI) }) }) }) {
                ops.mul({ kf.get() }, { ops.c(PIO2_LO) })
            }
        }
        t.set { ops.mul({ r.get() }, { r.get() }) }
        horner(ops, t, acc, factorialSeries(11, 1, 2, true))
        sr.set { ops.mul({ acc.get() }, { r.get() }) }
        horner(ops, t, acc, factorialSeries(11, 0, 2, true))
        cr.set { acc.get() }
        // sin: k=0 sr, 1 cr, 2 -sr, 3 -cr; cos: k=0 cr, 1 -sr, 2 -cr, 3 sr
        q.set {
            k.get()
            if (cosine) {
                e.const(1)
                e.op(Add)
                e.const(4)
                e.op(Mod)
            }
        }
        e.ifElseValue(label("odd").hint, {
            q.get()
            e.const(2)
            e.op(Mod)
        }, { cr.get() }) { sr.get() }
        t.set { }
        e.ifElseValue(label("neg").hint, {
            q.get()
            e.const(1)
            e.op(Greater)
        }, { ops.neg { t.get() } }) { t.get() }
        ret(true)
    }
}

/** atan x: reflect to |x| <= 1, shift by pi/6 to |x| <= tan(pi/12), then a series. */
val Runtime.floatAtan: Label get() {
    val rt = this
    return routine("f64_atan", 1) {
        val L = Locals(this)
        val x = L.V(param(0))
        val neg = L.v()
        val inv = L.v()
        val shift = L.v()
        val t = L.v()
        val acc = L.v()
        val ops = FloatOps(this, rt)
        L.begin()
        e.ifThen(label("nan").hint, { rt.isNaNCall(frame) { x.get() } }) {
            x.get()
            ret(true)
        }
        neg.set { ops.lt({ x.get() }, { ops.c(0.0) }) }
        e.ifThen(label("neg").hint, { neg.get() }) { x.set { ops.neg { x.get() } } }
        inv.set { ops.lt({ ops.c(1.0) }, { x.get() }) }
        e.ifThen(label("inv").hint, { inv.get() }) { x.set { ops.div({ ops.c(1.0) }, { x.get() }) } }
        shift.set { ops.lt({ ops.c(0.2679491924311227) }, { x.get() }) }
        e.ifThen(label("shift").hint, { shift.get() }) {
            // (x - 1/sqrt3) / (1 + x/sqrt3)
            x.set {
                ops.div({ ops.sub({ x.get() }, { ops.c(0.5773502691896258) }) }) {
                    ops.add({ ops.c(1.0) }, { ops.mul({ x.get() }, { ops.c(0.5773502691896258) }) })
                }
            }
        }
        t.set { ops.mul({ x.get() }, { x.get() }) }
        horner(ops, t, acc, (0 until 16).map { (if (it % 2 == 1) -1.0 else 1.0) / (2 * it + 1) }.reversed())
        acc.set { ops.mul({ acc.get() }, { x.get() }) }
        e.ifThen(label("unshift").hint, { shift.get() }) {
            acc.set { ops.add({ acc.get() }, { ops.c(Math.PI / 6) }) }
        }
        e.ifThen(label("uninv").hint, { inv.get() }) {
            acc.set { ops.sub({ ops.c(Math.PI / 2) }, { acc.get() }) }
        }
        e.ifThen(label("unneg").hint, { neg.get() }) { acc.set { ops.neg { acc.get() } } }
        acc.get()
        ret(true)
    }
}

/** Composite functions, each a few calls to the ones above. */
fun Runtime.floatFunction(name: String): Label {
    val rt = this
    val arity = if (name in setOf("atan2", "pow", "min", "max")) 2 else 1
    return routine("f64_fn_$name", arity) {
        val L = Locals(this)
        val x = L.V(param(0))
        val y = if (arity == 2) L.V(param(1)) else null
        val a = L.v()
        val b = L.v()
        val ops = FloatOps(this, rt)
        L.begin()
        fun exp(push: () -> Unit) = ops.one(rt.floatExp, push)
        fun log(push: () -> Unit) = ops.one(rt.floatLog, push)
        fun returnIf(n: String, cond: () -> Unit, push: () -> Unit) {
            e.ifThen(label(n).hint, cond) {
                push()
                ret(true)
            }
        }
        fun isNaN(push: () -> Unit) = rt.isNaNCall(frame, push)
        when (name) {
            "log10" -> ops.div({ log { x.get() } }, { ops.c(Math.log(10.0)) })
            "log1p" -> log { ops.add({ ops.c(1.0) }, { x.get() }) }
            "expm1" -> {
                // For small x, the series; e^x - 1 loses the low bits there.
                returnIf("small", { ops.lt({ ops.abs { x.get() } }, { ops.c(0.5) }) }) {
                    a.set { ops.c(1.0 / 6227020800.0) }
                    for (i in 12 downTo 1) {
                        var fact = 1.0
                        for (j in 2..i) fact *= j
                        a.set { ops.add({ ops.mul({ a.get() }, { x.get() }) }, { ops.c(1.0 / fact) }) }
                    }
                    ops.mul({ a.get() }, { x.get() })
                }
                ops.sub({ exp { x.get() } }, { ops.c(1.0) })
            }
            "sinh", "cosh", "tanh" -> {
                a.set { exp { x.get() } }
                b.set { ops.div({ ops.c(1.0) }, { a.get() }) }
                when (name) {
                    "sinh" -> ops.mul({ ops.sub({ a.get() }, { b.get() }) }, { ops.c(0.5) })
                    "cosh" -> ops.mul({ ops.add({ a.get() }, { b.get() }) }, { ops.c(0.5) })
                    else -> {
                        returnIf("big", { ops.lt({ ops.c(20.0) }, { ops.abs { x.get() } }) }) {
                            e.ifElseValue(label("sgn").hint, { ops.lt({ x.get() }, { ops.c(0.0) }) }, { ops.c(-1.0) }) {
                                ops.c(1.0)
                            }
                        }
                        ops.div({ ops.sub({ a.get() }, { b.get() }) }, { ops.add({ a.get() }, { b.get() }) })
                    }
                }
            }
            "tan" -> ops.div({ ops.one(rt.floatTrig(false)) { x.get() } }, { ops.one(rt.floatTrig(true)) { x.get() } })
            "asin", "acos" -> {
                returnIf("range", { ops.lt({ ops.c(1.0) }, { ops.abs { x.get() } }) }) { ops.c(Double.NaN) }
                // asin x = atan(x / sqrt(1 - x^2)), with |x| = 1 giving +-pi/2
                a.set { ops.one(rt.floatSqrt) { ops.sub({ ops.c(1.0) }, { ops.mul({ x.get() }, { x.get() }) }) } }
                e.ifElse(label("edge").hint, {
                    ops.cmp({ a.get() }, { ops.c(0.0) })
                    e.op(Not)
                }, {
                    b.set {
                        e.ifElseValue(label("s").hint, { ops.lt({ x.get() }, { ops.c(0.0) }) }, {
                            ops.c(-Math.PI / 2)
                        }) { ops.c(Math.PI / 2) }
                    }
                }) {
                    b.set { ops.one(rt.floatAtan) { ops.div({ x.get() }, { a.get() }) } }
                }
                if (name == "asin") b.get() else ops.sub({ ops.c(Math.PI / 2) }, { b.get() })
            }
            "atan2" -> {
                // this is y, the argument x: atan2(y = x, x = y!!)
                val yy = x
                val xx = y!!
                returnIf("nan", {
                    isNaN { yy.get() }
                    isNaN { xx.get() }
                    e.op(Add)
                }) { ops.c(Double.NaN) }
                e.ifElse(label("xzero").hint, {
                    ops.cmp({ ops.abs { xx.get() } }, { ops.c(0.0) })
                    e.op(Not)
                }, {
                    a.set {
                        e.ifElseValue(label("ysgn").hint, { ops.lt({ yy.get() }, { ops.c(0.0) }) }, {
                            ops.c(-Math.PI / 2)
                        }) {
                            e.ifElseValue(label("yzero").hint, {
                                ops.cmp({ ops.abs { yy.get() } }, { ops.c(0.0) })
                                e.op(Not)
                            }, { yy.get() }) { ops.c(Math.PI / 2) }
                        }
                    }
                }) {
                    a.set { ops.one(rt.floatAtan) { ops.div({ yy.get() }, { xx.get() }) } }
                    e.ifThen(label("left").hint, { ops.lt({ xx.get() }, { ops.c(0.0) }) }) {
                        a.set {
                            e.ifElseValue(label("ylow").hint, { ops.lt({ yy.get() }, { ops.c(0.0) }) }, {
                                ops.sub({ a.get() }, { ops.c(Math.PI) })
                            }) { ops.add({ a.get() }, { ops.c(Math.PI) }) }
                        }
                    }
                }
                a.get()
            }
            "pow" -> {
                // x ** y. Integral y by squaring; y = +-0.5 by sqrt; else e^(y log x).
                returnIf("zero", {
                    ops.cmp({ ops.abs { y!!.get() } }, { ops.c(0.0) })
                    e.op(Not)
                }) { ops.c(1.0) }
                returnIf("nan", {
                    isNaN { x.get() }
                    isNaN { y!!.get() }
                    e.op(Add)
                }) { ops.c(Double.NaN) }
                returnIf("half", {
                    ops.cmp({ y!!.get() }, { ops.c(0.5) })
                    e.op(Not)
                }) { ops.one(rt.floatSqrt) { x.get() } }
                returnIf("mhalf", {
                    ops.cmp({ y!!.get() }, { ops.c(-0.5) })
                    e.op(Not)
                }) { ops.div({ ops.c(1.0) }, { ops.one(rt.floatSqrt) { x.get() } }) }
                // integral and small: |y| < 2^31 and trunc(y) == y
                e.ifThen(label("int").hint, {
                    ops.cmp({ ops.one(rt.floatTrunc) { y!!.get() } }, { y!!.get() })
                    e.op(Not)
                    ops.lt({ ops.abs { y!!.get() } }, { ops.c(2147483647.0) })
                    e.op(Multiply)
                }) {
                    val n = L.V(b.slot)
                    n.set {
                        call(frame, rt.floatToInt64(false), 1, true) { y!!.get() }
                        frame.unpack64()
                    }
                    a.set { ops.c(1.0) }
                    // result in a, base in x, exponent |n|
                    val absN = L.V(n.slot)
                    val sgn = L.V(y!!.slot)
                    sgn.set {
                        e.const(0)
                        n.get()
                        e.op(Greater)
                    }
                    absN.set {
                        n.get()
                        sgn.get()
                        e.const(-2)
                        e.op(Multiply)
                        e.const(1)
                        e.op(Add)
                        e.op(Multiply)
                    }
                    e.whileLoop(label("sq").hint, {
                        absN.get()
                        e.const(0)
                        e.op(Greater)
                    }) {
                        e.ifThen(label("bit").hint, {
                            absN.get()
                            e.const(2)
                            e.op(Mod)
                        }) {
                            a.set { ops.mul({ a.get() }, { x.get() }) }
                        }
                        x.set { ops.mul({ x.get() }, { x.get() }) }
                        absN.set {
                            absN.get()
                            e.const(2)
                            e.op(Divide)
                        }
                    }
                    e.ifThen(label("recip").hint, { sgn.get() }) { a.set { ops.div({ ops.c(1.0) }, { a.get() }) } }
                    a.get()
                    ret(true)
                }
                // negative base with a non-integral exponent
                returnIf("negbase", { ops.lt({ x.get() }, { ops.c(0.0) }) }) { ops.c(Double.NaN) }
                returnIf("zbase", {
                    ops.cmp({ ops.abs { x.get() } }, { ops.c(0.0) })
                    e.op(Not)
                }) {
                    e.ifElseValue(label("zs").hint, { ops.lt({ y!!.get() }, { ops.c(0.0) }) }, {
                        ops.c(Double.POSITIVE_INFINITY)
                    }) { ops.c(0.0) }
                }
                exp { ops.mul({ y!!.get() }, { log { x.get() } }) }
            }
            "sign" -> {
                returnIf("nan", { isNaN { x.get() } }) { x.get() }
                returnIf("zero", {
                    ops.cmp({ ops.abs { x.get() } }, { ops.c(0.0) })
                    e.op(Not)
                }) { x.get() }
                e.ifElseValue(label("s").hint, { ops.lt({ x.get() }, { ops.c(0.0) }) }, { ops.c(-1.0) }) { ops.c(1.0) }
            }
            "min", "max" -> {
                returnIf("nan", {
                    isNaN { x.get() }
                    isNaN { y!!.get() }
                    e.op(Add)
                }) { ops.c(Double.NaN) }
                e.ifElseValue(label("pick").hint, {
                    ops.lt({ x.get() }, { y!!.get() })
                }, { if (name == "min") x.get() else y!!.get() }) { if (name == "min") y!!.get() else x.get() }
            }
            else -> error(name)
        }
        ret(true)
    }
}

/**
 * Python's math.isclose: |a - b| <= max(max(|a|, |b|) * relTol, absTol).
 * NaN is close to nothing, itself included.
 */
val Runtime.floatNear: Label get() {
    val rt = this
    return routine("f64_near", 4) {
        val L = Locals(this)
        val a = L.V(param(0))
        val b = L.V(param(1))
        val rel = L.V(param(2))
        val abs = L.V(param(3))
        val margin = L.v()
        val ops = FloatOps(this, rt)
        L.begin()
        e.ifThen(label("nan").hint, {
            rt.isNaNCall(frame) { a.get() }
            rt.isNaNCall(frame) { b.get() }
            e.op(Add)
        }) {
            e.const(0)
            ret(true)
        }
        margin.set {
            ops.mul({
                call(frame, rt.floatFunction("max"), 2, true) {
                    ops.abs { a.get() }
                    ops.abs { b.get() }
                }
            }, { rel.get() })
        }
        margin.set { call(frame, rt.floatFunction("max"), 2, true) { margin.get(); abs.get() } }
        ops.lt({ margin.get() }, { ops.abs { ops.sub({ a.get() }, { b.get() }) } })
        e.op(Not)
        ret(true)
    }
}

/** Float64 to Int32: saturating (NaN 0), or checked to bubble unless within 1 of the result. */
fun Runtime.floatToInt32(checked: Boolean): Label {
    val rt = this
    return routine("f64_to_i32_${if (checked) "checked" else "sat"}", 1) {
        val L = Locals(this)
        val x = L.V(param(0))
        val i = L.v()
        val v = L.wide()
        val ops = FloatOps(this, rt)
        L.begin()
        v.set {
            call(frame, rt.floatToInt64(false), 1, true) { x.get() }
            frame.unpack64()
        }
        // clamp to Int32, comparing as wide values held in a local
        i.set {
            e.ifElseValue(label("lo").hint, {
                e.const(Int.MIN_VALUE.toLong())
                v.get()
                e.op(Greater)
            }, { e.const(Int.MIN_VALUE.toLong()) }) {
                e.ifElseValue(label("hi").hint, {
                    v.get()
                    e.const(Int.MAX_VALUE.toLong())
                    e.op(Greater)
                }, { e.const(Int.MAX_VALUE.toLong()) }) { v.get() }
            }
        }
        if (checked) {
            // |x - i| < 1, false for NaN
            val ok = label("close")
            ops.lt({ ops.abs { ops.sub({ x.get() }, { call(frame, rt.floatFromInt, 1, true) { i.get() } }) } }, {
                ops.c(1.0)
            })
            e.op(Not)
            e.op(Not)
            e.branch(ok, failLanding(e.depth - 1))
            e.startBlock(ok)
        }
        i.get()
        ret(true)
        end()
    }
}

/** Int64 to Float64; checked bubbles outside +/-(2^53 - 1), as be-js does. */
fun Runtime.int64ToFloat(checked: Boolean): Label {
    val rt = this
    return routine("i64_to_f64_${if (checked) "checked" else "unsafe"}", 1) {
        val L = Locals(this)
        val n = L.V(param(0))
        val v = L.wide()
        L.begin()
        if (checked) {
            v.set {
                n.get()
                frame.unpack64()
            }
            val ok = label("safe")
            e.const((1L shl 53))
            v.get()
            e.op(Greater)
            v.get()
            e.const(-(1L shl 53))
            e.op(Greater)
            e.op(Multiply)
            e.branch(ok, failLanding(e.depth - 1))
            e.startBlock(ok)
        }
        call(frame, rt.floatFromInt64, 1, true) { n.get() }
        ret(true)
        end()
    }
}
