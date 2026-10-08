package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Pop
import lang.temper.be.piet.PietOp.Subtract

/*
 * Float64 to String: the shortest digits that read back as the same double,
 * by Burger and Dybvig's free-format algorithm over bignums, laid out the
 * way JavaScript's Number.prototype.toString lays them out, with a ".0"
 * wherever that leaves no fraction or exponent mark: 1.0, 0.5, 1.0e+25,
 * 9.9999999999899e+25. That is Temper's format (be-js's float64ToString
 * is exactly JS formatting plus the ".0").
 */

private fun FunctionWriter.appendAscii(text: String) {
    for (c in text) {
        e.const(c.code)
        frame.append()
    }
}

/** A string literal made at run time, interned. */
private fun FunctionWriter.literal(rt: Runtime, text: String) {
    frame.stringLiteral(text)
    rt.internTop(frame)
}

val Runtime.floatToString: Label get() {
    val rt = this
    val shl = bnShiftLeft
    val mul = bnMulSmall
    val pow10 = bnMulPow10
    val fromI64 = bnFromInt64
    val cmp = bnCmp
    val sub = bnSub
    val add = bnAdd
    val lbAdd = listBuilderAdd
    return routine("f64_to_string", 1) {
        val L = Locals(this)
        val box = L.V(param(0))
        val s = L.v()
        val ef = L.v()
        val e2 = L.v()
        val k = L.v()
        val roundOk = L.v()
        val d = L.v()
        val tc1 = L.v()
        val tc2 = L.v()
        val n = L.v()
        val i = L.v()
        val header = L.v()
        val digits = L.v()
        val r = L.v()
        val sc = L.v()
        val mp = L.v()
        val mm = L.v()
        val tmp = L.v()
        val unequal = L.v()
        val up = L.v()
        val frac = L.wide()
        val bits = L.wide()
        val f = L.wide()
        val fc = FloatCode(this, L)
        L.begin()
        fc.unpack(box, s, ef, frac, bits)
        fun returnText(name: String, cond: () -> Unit, text: () -> Unit) {
            val yes = label("$name.yes")
            val no = label("$name.no")
            cond()
            val d0 = e.depth - 1
            e.branch(yes, no)
            e.startBlock(yes, d0)
            text()
            ret(true)
            e.startBlock(no, d0)
        }
        returnText("nan", { fc.isNaN(ef, frac) }) { literal(rt, "NaN") }
        returnText("inf", { fc.isInf(ef, frac) }) {
            e.ifElseValue(label("infsign").hint, { s.get() }, { literal(rt, "-Infinity") }) { literal(rt, "Infinity") }
        }
        returnText("zero", { fc.isZero(ef, frac) }) {
            e.ifElseValue(label("zerosign").hint, { s.get() }, { literal(rt, "-0.0") }) { literal(rt, "0.0") }
        }
        // value = f * 2^e2
        f.set { frac.get() }
        fc.implicit(ef, f, ef)
        e2.set {
            ef.get()
            e.const(1075)
            e.op(Subtract)
        }
        roundOk.set {
            f.get()
            e.const(2)
            e.op(PietOp.Mod)
            e.op(Not)
        }
        // r, s, m+, m- per Burger & Dybvig.
        fun newFromF() = call(frame, fromI64, 1, true) {
            f.get()
            frame.pack64()
        }
        fun newSmall(v: Int) {
            // A new bignum is 0; add v to it. It is under ret, fail and D.
            e.newBignum(frame)
            call(frame, rt.bnAddSmall, 2, false) {
                e.pick(4)
                e.const(v)
            }
        }
        // f == 2^52 and not the smallest exponent: the gap below is half the gap above.
        unequal.set {
            f.get()
            e.const(1L shl 52)
            e.op(Subtract)
            e.op(Not)
            ef.get()
            e.const(1)
            e.op(Greater)
            e.op(Multiply)
        }
        e.ifElse(label("pos").hint, {
            e2.get()
            e.const(-1)
            e.op(Greater)
        }, {
            // r = f * 2^e2 * 2 (* 2 more if unequal), s = 2 (or 4), m+ = 2^e2 (or 2^(e2+1)), m- = 2^e2
            r.set { newFromF() }
            call(frame, shl, 2, false) {
                r.get()
                e2.get()
                e.const(1)
                e.op(Add)
                unequal.get()
                e.op(Add)
            }
            sc.set { newSmall(1) }
            call(frame, shl, 2, false) {
                sc.get()
                e.const(1)
                unequal.get()
                e.op(Add)
            }
            mp.set { newSmall(1) }
            call(frame, shl, 2, false) {
                mp.get()
                e2.get()
                unequal.get()
                e.op(Add)
            }
            mm.set { newSmall(1) }
            call(frame, shl, 2, false) {
                mm.get()
                e2.get()
            }
        }) {
            // r = f * 2 (* 2), s = 2^(1 - e2) (* 2), m+ = 1 (or 2), m- = 1
            r.set { newFromF() }
            call(frame, shl, 2, false) {
                r.get()
                e.const(1)
                unequal.get()
                e.op(Add)
            }
            sc.set { newSmall(1) }
            call(frame, shl, 2, false) {
                sc.get()
                e.const(1)
                e2.get()
                e.op(Subtract)
                unequal.get()
                e.op(Add)
            }
            mp.set { newSmall(1) }
            call(frame, shl, 2, false) {
                mp.get()
                unequal.get()
            }
            mm.set { newSmall(1) }
        }
        tmp.set { e.newBignum(frame) }
        // est = floor((e2 + bitlen(f) - 1) * log10(2)), never above the true k.
        n.set {
            // bitlen(f): f is below 2^53
            e.const(0)
        }
        // count bits of f by halving a copy kept in bits
        bits.set { f.get() }
        e.whileLoop(label("flen").hint, {
            bits.get()
            e.op(Not)
            e.op(Not)
        }) {
            bits.set {
                bits.get()
                e.const(2)
                e.op(Divide)
            }
            n.set {
                n.get()
                e.const(1)
                e.op(Add)
            }
        }
        k.set {
            e2.get()
            n.get()
            e.op(Add)
            e.const(1)
            e.op(Subtract)
            e.const(1292913986)
            e.op(Multiply)
            e.floorDiv { e.const(TWO_32) }
        }
        e.ifElse(label("scale").hint, {
            k.get()
            e.const(-1)
            e.op(Greater)
        }, {
            call(frame, pow10, 2, false) {
                sc.get()
                k.get()
            }
        }) {
            for (x in listOf(r, mp, mm)) {
                call(frame, pow10, 2, false) {
                    x.get()
                    e.const(0)
                    k.get()
                    e.op(Subtract)
                }
            }
        }
        // high: r + m+ >= s (or > when not roundOk), compared via a copy of r.
        fun high() {
            call(frame, rt.bnAssign, 2, false) {
                tmp.get()
                r.get()
            }
            call(frame, add, 2, false) {
                tmp.get()
                mp.get()
            }
            call(frame, cmp, 2, true) {
                tmp.get()
                sc.get()
            }
            // cmp + roundOk > 0
            roundOk.get()
            e.op(Add)
            e.const(0)
            e.op(Greater)
        }
        // Fixup: while too low, s *= 10, k++.
        e.whileLoop(label("fixup").hint, { high() }) {
            call(frame, mul, 2, false) {
                sc.get()
                e.const(10)
            }
            k.set {
                k.get()
                e.const(1)
                e.op(Add)
            }
        }
        for (x in listOf(r, mp, mm)) {
            call(frame, mul, 2, false) {
                x.get()
                e.const(10)
            }
        }
        digits.set {
            e.const(0)
            e.const(0)
            e.const(PietTranslator.NULL)
            frame.alloc(3)
        }
        e.loop(label("gen").hint, { e.const(1) }) { exit ->
            d.set { e.const(0) }
            e.whileLoop(label("digit").hint, {
                call(frame, cmp, 2, true) {
                    r.get()
                    sc.get()
                }
                e.const(-1)
                e.op(Greater)
            }) {
                call(frame, sub, 2, false) {
                    r.get()
                    sc.get()
                }
                d.set {
                    d.get()
                    e.const(1)
                    e.op(Add)
                }
            }
            // tc1: r < m- (<= when roundOk)
            tc1.set {
                call(frame, cmp, 2, true) {
                    r.get()
                    mm.get()
                }
                roundOk.get()
                e.op(Subtract)
                e.const(0)
                e.swap()
                e.op(Greater)
            }
            tc2.set { high() }
            val more = label("more")
            val last = label("last")
            tc1.get()
            tc2.get()
            e.op(Add)
            e.branch(last, more)
            e.startBlock(last)
            // The last digit: up when only m+ allows it, or when both do and
            // 2r >= s (rounding half up).
            up.set {
                tc2.get()
                tc1.get()
                e.op(Not)
                e.op(Multiply)
            }
            e.ifThen(label("both").hint, {
                tc1.get()
                tc2.get()
                e.op(Multiply)
            }) {
                call(frame, rt.bnAssign, 2, false) {
                    tmp.get()
                    r.get()
                }
                call(frame, mul, 2, false) {
                    tmp.get()
                    e.const(2)
                }
                up.set {
                    call(frame, cmp, 2, true) {
                        tmp.get()
                        sc.get()
                    }
                    e.const(-1)
                    e.op(Greater)
                }
            }
            call(frame, lbAdd, 2, false) {
                digits.get()
                d.get()
                up.get()
                e.op(Add)
            }
            e.jump(exit)
            e.startBlock(more)
            call(frame, lbAdd, 2, false) {
                digits.get()
                d.get()
            }
            for (x in listOf(r, mp, mm)) {
                call(frame, mul, 2, false) {
                    x.get()
                    e.const(10)
                }
            }
        }
        layoutDigits(rt, digits, k, s, n, i, header)
        ret(true)
    }
}

/**
 * Lays out digits d1..dn with decimal exponent k (value 0.d1..dn * 10^k) as
 * JavaScript would, plus the ".0" Temper adds, and pushes the interned string.
 */
private fun FunctionWriter.layoutDigits(
    rt: Runtime,
    digits: Locals.V,
    k: Locals.V,
    s: Locals.V,
    n: Locals.V,
    i: Locals.V,
    header: Locals.V,
) {
    n.set {
        digits.get()
        frame.heapLoad(LIST_LENGTH)
    }
    header.set {
        e.const(0)
        frame.alloc(1)
    }
    e.ifThen(label("minus").hint, { s.get() }) { appendAscii("-") }
    fun digit(index: () -> Unit) {
        digits.get()
        frame.heapLoad(LIST_DATA)
        index()
        e.op(Add)
        frame.heapLoad()
        e.const('0'.code)
        e.op(Add)
        frame.append()
    }

    /** Appends digits from i (set to [from] unless null) up to what [until] pushes. */
    fun digitsFrom(from: Int?, until: () -> Unit) {
        if (from != null) i.set { e.const(from) }
        e.whileLoop(label("digits").hint, {
            until()
            i.get()
            e.op(Greater)
        }) {
            digit { i.get() }
            i.set {
                i.get()
                e.const(1)
                e.op(Add)
            }
        }
    }
    val done = label("laidout")
    // n <= k <= 21: digits, zeros, ".0"
    val intForm = label("int")
    val notInt = label("notint")
    // k > n - 1
    k.get()
    n.get()
    e.const(1)
    e.op(Subtract)
    e.op(Greater)
    e.const(22)
    k.get()
    e.op(Greater)
    e.op(Multiply)
    e.branch(intForm, notInt)
    e.startBlock(intForm)
    digitsFrom(0) { n.get() }
    i.set { n.get() }
    e.whileLoop(label("zeros").hint, {
        k.get()
        i.get()
        e.op(Greater)
    }) {
        appendAscii("0")
        i.set {
            i.get()
            e.const(1)
            e.op(Add)
        }
    }
    appendAscii(".0")
    e.jump(done)
    e.startBlock(notInt)
    // 0 < k <= 21: d1..dk "." rest
    val mid = label("mid")
    val notMid = label("notmid")
    k.get()
    e.const(0)
    e.op(Greater)
    e.const(22)
    k.get()
    e.op(Greater)
    e.op(Multiply)
    e.branch(mid, notMid)
    e.startBlock(mid)
    digitsFrom(0) { k.get() }
    appendAscii(".")
    digitsFrom(null) { n.get() }
    e.jump(done)
    e.startBlock(notMid)
    // -6 < k <= 0: "0." zeros digits
    val small = label("small")
    val expo = label("expo")
    // -6 < k and !(k > 0)
    k.get()
    e.const(-6)
    e.op(Greater)
    k.get()
    e.const(0)
    e.op(Greater)
    e.op(Not)
    e.op(Multiply)
    e.branch(small, expo)
    e.startBlock(small)
    appendAscii("0.")
    i.set { k.get() }
    e.whileLoop(label("lead").hint, {
        e.const(0)
        i.get()
        e.op(Greater)
    }) {
        appendAscii("0")
        i.set {
            i.get()
            e.const(1)
            e.op(Add)
        }
    }
    digitsFrom(0) { n.get() }
    e.jump(done)
    e.startBlock(expo)
    // d1 [. rest | .0] e +/- (k - 1)
    digit { e.const(0) }
    appendAscii(".")
    e.ifElse(label("onedigit").hint, {
        n.get()
        e.const(1)
        e.op(Greater)
    }, { digitsFrom(1) { n.get() } }) { appendAscii("0") }
    appendAscii("e")
    k.set {
        k.get()
        e.const(1)
        e.op(Subtract)
    }
    e.ifElse(label("esign").hint, {
        e.const(0)
        k.get()
        e.op(Greater)
    }, {
        appendAscii("-")
        k.set {
            k.get()
            e.const(-1)
            e.op(Multiply)
        }
    }) { appendAscii("+") }
    // exponent digits, at most three
    for (place in intArrayOf(100, 10, 1)) {
        e.ifThen(label("ed$place").hint, {
            k.get()
            e.const(place - 1)
            e.op(Greater)
            if (place == 1) {
                e.op(Pop)
                e.const(1)
            }
        }) {
            k.get()
            e.const(place)
            e.op(Divide)
            e.const(10)
            e.op(PietOp.Mod)
            e.const('0'.code)
            e.op(Add)
            frame.append()
        }
    }
    e.jump(done)
    e.startBlock(done)
    frame.fillLength { header.get() }
    header.get()
    rt.internTop(frame)
}

/**
 * `s.toFloat64()`: Temper's grammar, which is be-js's
 * `\s*-?(\d+(\.\d+)?([eE][-+]?\d+)?|NaN|Infinity)\s*`, correctly rounded.
 *
 * Up to 53 significant bits and a decimal exponent within 22 of zero is one
 * multiplication or division by an exact power of ten, which rounds
 * correctly (Clinger's fast path). Anything else goes through bignums: a
 * product truncated to 56 bits with a sticky bit, or a long division with
 * one.
 */
val Runtime.floatParse: Label get() {
    val rt = this
    val mul10 = bnMulSmall
    val addSmall = bnAddSmall
    val bitLen = bnBitLength
    val toI64 = bnToInt64
    val pow10 = bnMulPow10
    val shl = bnShiftLeft
    val cmp = bnCmp
    val sub = bnSub
    val assign = bnAssign
    val divSmall = bnDivSmallSticky
    val fromI64 = floatFromInt64
    val fmul = floatMul
    val fdiv = floatDiv
    val neg = floatNegate
    return routine("f64_parse", 1) {
        val L = Locals(this)
        val str = L.V(param(0))
        val i = L.v()
        val end = L.v()
        val negative = L.v()
        val c = L.v()
        val digits = L.v()
        val fracDigits = L.v()
        val ex = L.v()
        val exNeg = L.v()
        val n = L.v()
        val v = L.v()
        val p = L.v()
        val b = L.v()
        val bl = L.v()
        val sticky = L.v()
        val big = L.v()
        val t = L.v()
        val sgn = L.v()
        val q = L.wide()
        val fc = FloatCode(this, L)
        L.begin()
        val bad = failLanding()
        fun cp(at: () -> Unit) {
            str.get()
            at()
            e.op(Add)
            frame.heapLoad(1)
        }
        fun isSpace(at: () -> Unit) {
            cp(at)
            e.op(PietOp.Duplicate)
            e.const(32)
            e.op(Subtract)
            e.op(Not)
            e.swap()
            e.op(PietOp.Duplicate)
            e.const(8)
            e.op(Greater)
            e.swap()
            e.const(14)
            e.swap()
            e.op(Greater)
            e.op(Multiply)
            e.op(Add)
        }
        fun more() {
            end.get()
            i.get()
            e.op(Greater)
        }
        fun isDigitHere() {
            // i < end && '0' <= c <= '9', reading c only when i < end
            c.set { e.const(0) }
            e.ifThen(label("rd").hint, { more() }) { c.set { cp { i.get() } } }
            c.get()
            e.const('0'.code - 1)
            e.op(Greater)
            e.const('9'.code + 1)
            c.get()
            e.op(Greater)
            e.op(Multiply)
        }
        fun step() = i.set {
            i.get()
            e.const(1)
            e.op(Add)
        }
        fun failUnless(name: String, cond: () -> Unit) {
            val ok = label(name)
            cond()
            e.branch(ok, failLanding(e.depth - 1))
            e.startBlock(ok)
        }
        end.set {
            str.get()
            frame.heapLoad()
        }
        e.loop(label("lead").hint, { more() }) { exit ->
            val go = label("go")
            isSpace { i.get() }
            e.branch(go, exit)
            e.startBlock(go)
            step()
        }
        e.loop(label("trail").hint, { more() }) { exit ->
            val go = label("go")
            isSpace {
                end.get()
                e.const(1)
                e.op(Subtract)
            }
            e.branch(go, exit)
            e.startBlock(go)
            end.set {
                end.get()
                e.const(1)
                e.op(Subtract)
            }
        }
        failUnless("nonempty") { more() }
        e.ifThen(label("minus").hint, {
            cp { i.get() }
            e.const('-'.code)
            e.op(Subtract)
            e.op(Not)
        }) {
            negative.set { e.const(1) }
            step()
        }
        failUnless("after sign") { more() }
        // NaN and Infinity: compare the rest exactly.
        fun matches(word: String) {
            // end - i == len && each char matches
            end.get()
            i.get()
            e.op(Subtract)
            e.const(word.length)
            e.op(Subtract)
            e.op(Not)
            e.ifElseValue(label("len${word}").hint, {}, {
                e.const(1)
                for ((j, ch) in word.withIndex()) {
                    cp {
                        i.get()
                        e.const(j)
                        e.op(Add)
                    }
                    e.const(ch.code)
                    e.op(Subtract)
                    e.op(Not)
                    e.op(Multiply)
                }
            }) { e.const(0) }
        }
        val words = label("words")
        val number = label("number")
        cp { i.get() }
        e.const('9'.code)
        e.op(Greater)
        e.branch(words, number)
        e.startBlock(words)
        e.ifThen(label("nan").hint, { matches("NaN") }) {
            fc.pushNaN()
            ret(true)
        }
        e.ifThen(label("inf").hint, { matches("Infinity") }) {
            fc.pushSignedInfinity(negative)
            ret(true)
        }
        e.jump(bad)
        e.startBlock(number)
        n.set { e.newBignum(frame) }
        fun digitRun(counter: Locals.V?) {
            failUnless("digit") { isDigitHere() }
            e.whileLoop(label("run").hint, { isDigitHere() }) {
                call(frame, mul10, 2, false) {
                    n.get()
                    e.const(10)
                }
                call(frame, addSmall, 2, false) {
                    n.get()
                    c.get()
                    e.const('0'.code)
                    e.op(Subtract)
                }
                digits.set {
                    digits.get()
                    e.const(1)
                    e.op(Add)
                }
                if (counter != null) {
                    counter.set {
                        counter.get()
                        e.const(1)
                        e.op(Add)
                    }
                }
                step()
            }
        }
        digitRun(null)
        e.ifThen(label("point").hint, {
            more()
            e.ifElseValue(label("pt").hint, {}, {
                cp { i.get() }
                e.const('.'.code)
                e.op(Subtract)
                e.op(Not)
            }) { e.const(0) }
        }) {
            step()
            digitRun(fracDigits)
        }
        e.ifThen(label("exp").hint, {
            more()
            e.ifElseValue(label("ee").hint, {}, {
                cp { i.get() }
                e.op(PietOp.Duplicate)
                e.const('e'.code)
                e.op(Subtract)
                e.op(Not)
                e.swap()
                e.const('E'.code)
                e.op(Subtract)
                e.op(Not)
                e.op(Add)
            }) { e.const(0) }
        }) {
            step()
            failUnless("exp digits") { more() }
            e.ifThen(label("esign").hint, {
                cp { i.get() }
                e.op(PietOp.Duplicate)
                e.const('-'.code)
                e.op(Subtract)
                e.op(Not)
                e.op(PietOp.Duplicate)
                exNeg.set { }
                e.swap()
                e.const('+'.code)
                e.op(Subtract)
                e.op(Not)
                e.op(Add)
            }) { step() }
            failUnless("exp digit") { isDigitHere() }
            e.whileLoop(label("erun").hint, { isDigitHere() }) {
                // Saturate rather than overflow; anything past this is 0 or infinity anyway.
                ex.set {
                    ex.get()
                    e.const(10)
                    e.op(Multiply)
                    c.get()
                    e.const('0'.code)
                    e.op(Subtract)
                    e.op(Add)
                    e.const(100_000)
                    e.minTop()
                }
                step()
            }
        }
        failUnless("all of it") {
            more()
            e.op(Not)
        }
        // exponent = ±ex - fracDigits
        ex.set {
            ex.get()
            exNeg.get()
            e.const(-2)
            e.op(Multiply)
            e.const(1)
            e.op(Add)
            e.op(Multiply)
            fracDigits.get()
            e.op(Subtract)
        }
        sgn.set { negative.get() }
        e.ifThen(label("zero").hint, {
            n.get()
            frame.heapLoad(LIST_LENGTH)
            e.op(Not)
        }) {
            fc.pushSignedZero(sgn)
            ret(true)
        }
        bl.set { call(frame, bitLen, 1, true) { n.get() } }
        // Fast path.
        e.ifThen(label("fast").hint, {
            e.const(54)
            bl.get()
            e.op(Greater)
            e.const(23)
            ex.get()
            e.op(Greater)
            e.op(Multiply)
            ex.get()
            e.const(-23)
            e.op(Greater)
            e.op(Multiply)
        }) {
            v.set {
                call(frame, fromI64, 1, true) { call(frame, toI64, 1, true) { n.get() } }
            }
            // p = 10.0^|ex|, exact
            p.set {
                e.const(1.0.toRawBits())
                frame.pack64()
            }
            b.set {
                ex.get()
                e.op(PietOp.Duplicate)
                e.const(0)
                e.swap()
                e.op(Greater)
                e.const(-2)
                e.op(Multiply)
                e.const(1)
                e.op(Add)
                e.op(Multiply)
            }
            e.whileLoop(label("p10").hint, {
                b.get()
                e.const(0)
                e.op(Greater)
            }) {
                p.set {
                    call(frame, fmul, 2, true) {
                        p.get()
                        e.const(10.0.toRawBits())
                        frame.pack64()
                    }
                }
                b.set {
                    b.get()
                    e.const(1)
                    e.op(Subtract)
                }
            }
            e.ifElse(label("muldiv").hint, {
                ex.get()
                e.const(-1)
                e.op(Greater)
            }, {
                v.set {
                    call(frame, fmul, 2, true) {
                        v.get()
                        p.get()
                    }
                }
            }) {
                v.set {
                    call(frame, fdiv, 2, true) {
                        v.get()
                        p.get()
                    }
                }
            }
            e.ifThen(label("neg").hint, { negative.get() }) { v.set { call(frame, neg, 1, true) { v.get() } } }
            v.get()
            ret(true)
        }
        // Slow path. Far out of range is 0 or infinity.
        e.ifThen(label("huge").hint, {
            digits.get()
            ex.get()
            e.op(Add)
            e.const(310)
            e.op(Greater)
        }) {
            fc.pushSignedInfinity(sgn)
            ret(true)
        }
        e.ifThen(label("tiny").hint, {
            e.const(-330)
            digits.get()
            ex.get()
            e.op(Add)
            e.op(Greater)
        }) {
            fc.pushSignedZero(sgn)
            ret(true)
        }
        e.ifElse(label("posexp").hint, {
            ex.get()
            e.const(-1)
            e.op(Greater)
        }, {
            // X = N * 10^ex, then its top 56 bits with sticky.
            call(frame, pow10, 2, false) {
                n.get()
                ex.get()
            }
            bl.set { call(frame, bitLen, 1, true) { n.get() } }
            // Drop all but the top 56 bits; a 54- or 55-bit X drops none.
            b.set {
                bl.get()
                e.const(56)
                e.op(Subtract)
                e.const(0)
                e.maxTop()
            }
            t.set {
                b.get()
                e.const(1078)
                e.op(Add)
            }
            e.whileLoop(label("drop").hint, {
                b.get()
                e.const(LIMB_BITS - 1L)
                e.op(Greater)
            }) {
                sticky.set {
                    sticky.get()
                    call(frame, divSmall, 2, true) {
                        n.get()
                        e.const(LIMB)
                    }
                    e.op(Add)
                }
                b.set {
                    b.get()
                    e.const(LIMB_BITS)
                    e.op(Subtract)
                }
            }
            sticky.set {
                sticky.get()
                call(frame, divSmall, 2, true) {
                    n.get()
                    b.get()
                    e.pow2()
                }
                e.op(Add)
            }
            q.set {
                call(frame, toI64, 1, true) { n.get() }
                frame.unpack64()
            }
        }) {
            // q = floor(N * 2^sh / 10^-ex), sh making q at least 2^56.
            big.set {
                e.newBignum(frame)
                call(frame, addSmall, 2, false) {
                    e.pick(4)
                    e.const(1)
                }
            }
            call(frame, pow10, 2, false) {
                big.get()
                e.const(0)
                ex.get()
                e.op(Subtract)
            }
            b.set {
                // sh = max(0, 57 + bitlen(D) - bitlen(N))
                e.const(57)
                call(frame, bitLen, 1, true) { big.get() }
                e.op(Add)
                bl.get()
                e.op(Subtract)
                e.const(0)
                e.maxTop()
            }
            call(frame, shl, 2, false) {
                n.get()
                b.get()
            }
            t.set {
                e.const(1078)
                b.get()
                e.op(Subtract)
            }
            // Long division, one bit at a time from the top.
            p.set { e.newBignum(frame) }
            b.set {
                call(frame, bitLen, 1, true) { n.get() }
                call(frame, bitLen, 1, true) { big.get() }
                e.op(Subtract)
            }
            q.set { e.const(0) }
            e.whileLoop(label("bits").hint, {
                b.get()
                e.const(-1)
                e.op(Greater)
            }) {
                call(frame, assign, 2, false) {
                    p.get()
                    big.get()
                }
                call(frame, shl, 2, false) {
                    p.get()
                    b.get()
                }
                e.ifThen(label("fits").hint, {
                    call(frame, cmp, 2, true) {
                        n.get()
                        p.get()
                    }
                    e.const(-1)
                    e.op(Greater)
                }) {
                    call(frame, sub, 2, false) {
                        n.get()
                        p.get()
                    }
                    q.set {
                        q.get()
                        b.get()
                        e.pow2()
                        e.op(Add)
                    }
                }
                b.set {
                    b.get()
                    e.const(1)
                    e.op(Subtract)
                }
            }
            sticky.set {
                n.get()
                frame.heapLoad(LIST_LENGTH)
                e.op(Not)
                e.op(Not)
            }
        }
        // q is at least 2^55 here; bring it under 2^56 with sticky, and OR in sticky.
        e.whileLoop(label("narrow").hint, {
            q.get()
            e.const((1L shl 56) - 1)
            e.op(Greater)
        }) {
            q.set {
                q.get()
                e.shiftRightSticky { e.const(1) }
            }
            t.set {
                t.get()
                e.const(1)
                e.op(Add)
            }
        }
        fc.normalize(q, t, 55)
        q.set {
            q.get()
            sticky.get()
            e.op(Not)
            e.op(Not)
            q.get()
            e.const(2)
            e.op(PietOp.Mod)
            e.op(Not)
            e.op(Multiply)
            e.op(Add)
        }
        fc.roundPack(sgn, t, q)
        ret(true)
        end()
    }
}
