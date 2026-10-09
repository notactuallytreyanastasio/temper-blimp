package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Add
import lang.temper.be.piet.PietOp.Divide
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.Mod
import lang.temper.be.piet.PietOp.Multiply
import lang.temper.be.piet.PietOp.Not
import lang.temper.be.piet.PietOp.Subtract

/*
 * Strings are `length, code points...`. A StringIndex is a code point
 * offset from 0 to length, and StringIndex.none is -1, so comparing
 * indices, including against none, is comparing Ints.
 *
 * A routine that builds a string allocates its header, appends every code
 * point, and fills in the length, with no other allocation in between.
 */

private fun FunctionWriter.ld(slot: Int) = frame.load(slot)

private fun FunctionWriter.st(slot: Int, push: () -> Unit) {
    push()
    frame.store(slot)
}

private fun FunctionWriter.inc(slot: Int, by: Int = 1) = st(slot) {
    ld(slot)
    e.const(by)
    e.op(Add)
}

/** Pushes code point [index] (0-based) of the string in [s]. */
private fun FunctionWriter.codePoint(s: Int, index: () -> Unit) {
    ld(s)
    index()
    e.op(Add)
    frame.heapLoad(1)
}

private fun FunctionWriter.length(s: Int) {
    ld(s)
    frame.heapLoad()
}

/** `while (a < b)` over slots. */
private fun FunctionWriter.whileLess(name: String, a: () -> Unit, b: () -> Unit, body: () -> Unit) =
    e.whileLoop(label(name).hint, {
        b()
        a()
        e.op(Greater)
    }, body)

/** Pushes whether the top is a code point a string may hold: not a surrogate, at most 0x10FFFF. */
private fun FunctionWriter.validCodePoint(slot: Int) {
    // !(cp < 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))
    e.const(0)
    ld(slot)
    e.op(Greater)
    ld(slot)
    e.const(0x10FFFF)
    e.op(Greater)
    e.op(Add)
    ld(slot)
    e.const(0xD7FF)
    e.op(Greater)
    e.const(0xE000)
    ld(slot)
    e.op(Greater)
    e.op(Multiply)
    e.op(Add)
    e.op(Not)
}

/** The code points of [s] from [a] to [b], already clamped, as a new string. */
val Runtime.stringSlice: Label get() = routine("str_slice", 3) {
    begin(1)
    val s = param(0)
    val a = param(1)
    val b = param(2)
    val header = param(3)
    // Clamp both to [0, length] and b to at least a.
    st(a) {
        ld(a)
        e.clampTop({ e.const(0) }, { length(s) })
    }
    st(b) {
        ld(b)
        e.clampTop({ ld(a) }, { length(s) })
    }
    st(header) {
        e.const(0)
        frame.alloc(1)
    }
    whileLess("slice", { ld(a) }, { ld(b) }) {
        codePoint(s) { ld(a) }
        frame.append()
        inc(a)
    }
    frame.fillLength { ld(header) }
    ld(header)
    ret(true)
}

/** The first index at or after [start] where [needle] occurs in [s], or -1. */
val Runtime.stringIndexOf: Label get() = routine("str_index_of", 3) {
    begin(2)
    val s = param(0)
    val needle = param(1)
    val start = param(2)
    val j = param(3)
    val found = param(4)
    st(found) { e.const(-1) }
    st(start) {
        ld(start)
        e.const(0)
        e.maxTop()
    }
    // while (start + len(needle) <= len(s))
    e.loop(label("at").hint, {
        ld(start)
        length(needle)
        e.op(Add)
        length(s)
        e.op(Greater)
        e.op(Not)
    }) { exit ->
        st(j) { e.const(0) }
        e.loop(label("cmp").hint, {
            length(needle)
            ld(j)
            e.op(Greater)
        }) { mismatch ->
            val same = label("same")
            codePoint(s) {
                ld(start)
                ld(j)
                e.op(Add)
            }
            codePoint(needle) { ld(j) }
            e.op(Subtract)
            e.op(Not)
            e.branch(same, mismatch)
            e.startBlock(same)
            inc(j)
        }
        val hit = label("hit")
        val miss = label("miss")
        length(needle)
        ld(j)
        e.op(Subtract)
        e.op(Not)
        e.branch(hit, miss)
        e.startBlock(hit)
        st(found) { ld(start) }
        e.jump(exit)
        e.startBlock(miss)
        inc(start)
    }
    ld(found)
    ret(true)
}

/**
 * `s.split(sep)`. An empty separator splits into code points, and the
 * empty string splits into nothing. Otherwise the empty string is one
 * empty piece, and a separator at either end makes an empty piece there.
 */
val Runtime.stringSplit: Label get() {
    val slice = stringSlice
    val indexOf = stringIndexOf
    val add = listBuilderAdd
    return routine("str_split", 2) {
        begin(3)
        val s = param(0)
        val sep = param(1)
        val out = param(2)
        val from = param(3)
        val at = param(4)
        st(out) {
            e.const(0)
            e.const(0)
            e.const(PietTranslator.NULL)
            frame.alloc(3)
        }
        fun addPiece(a: () -> Unit, b: () -> Unit) {
            call(frame, add, 2, false) {
                ld(out)
                call(frame, slice, 3, true) {
                    ld(s)
                    a()
                    b()
                }
            }
        }
        e.ifElse(label("empty").hint, {
            length(sep)
            e.op(Not)
        }, {
            whileLess("cps", { ld(from) }, { length(s) }) {
                addPiece({ ld(from) }, {
                    ld(from)
                    e.const(1)
                    e.op(Add)
                })
                inc(from)
            }
        }) {
            e.loop(label("pieces").hint, { e.const(1) }) { exit ->
                st(at) {
                    call(frame, indexOf, 3, true) {
                        ld(s)
                        ld(sep)
                        ld(from)
                    }
                }
                val found = label("found")
                ld(at)
                e.const(-1)
                e.op(Subtract)
                e.op(Not)
                e.op(Not)
                e.branch(found, exit)
                e.startBlock(found)
                addPiece({ ld(from) }, { ld(at) })
                st(from) {
                    ld(at)
                    length(sep)
                    e.op(Add)
                }
            }
            addPiece({ ld(from) }, { length(s) })
        }
        ld(out)
        ret(true)
        end()
    }
}

val Runtime.stringForEach: Label get() = routine("str_foreach", 2) {
    begin(1)
    val s = param(0)
    val f = param(1)
    val i = param(2)
    whileLess("each", { ld(i) }, { length(s) }) {
        callClosure(frame, 1, false, failLanding(), { ld(f) }) { codePoint(s) { ld(i) } }
        inc(i)
    }
    ret(false)
    end()
}

/** `s[i]`, which panics out of range. */
val Runtime.stringGet: Label get() = routine("str_get", 2) {
    begin(0)
    val s = param(0)
    val i = param(1)
    val ok = label("ok")
    e.const(0)
    ld(i)
    e.op(Greater)
    length(s)
    ld(i)
    e.op(Greater)
    e.op(Not)
    e.op(Add)
    e.op(Not)
    e.branch(ok, failLanding(e.depth - 1))
    e.startBlock(ok)
    codePoint(s) { ld(i) }
    ret(true)
    end()
}

/** A one code point string, bubbling for a surrogate or anything past 0x10FFFF. */
val Runtime.stringFromCodePoint: Label get() = routine("str_from_cp", 1) {
    begin(0)
    val cp = param(0)
    val ok = label("ok")
    validCodePoint(cp)
    e.branch(ok, failLanding(e.depth - 1))
    e.startBlock(ok)
    e.const(1)
    ld(cp)
    frame.alloc(2)
    ret(true)
    end()
}

val Runtime.stringFromCodePoints: Label get() = routine("str_from_cps", 1) {
    begin(3)
    val list = param(0)
    val i = param(1)
    val cp = param(2)
    val header = param(3)
    // Check every code point before allocating anything.
    whileLess("check", { ld(i) }, { ld(list); frame.heapLoad(LIST_LENGTH) }) {
        st(cp) {
            ld(list)
            frame.heapLoad(LIST_DATA)
            ld(i)
            e.op(Add)
            frame.heapLoad()
        }
        val ok = label("ok")
        validCodePoint(cp)
        e.branch(ok, failLanding(e.depth - 1))
        e.startBlock(ok)
        inc(i)
    }
    st(header) {
        e.const(0)
        frame.alloc(1)
    }
    st(i) { e.const(0) }
    whileLess("copy", { ld(i) }, { ld(list); frame.heapLoad(LIST_LENGTH) }) {
        ld(list)
        frame.heapLoad(LIST_DATA)
        ld(i)
        e.op(Add)
        frame.heapLoad()
        frame.append()
        inc(i)
    }
    frame.fillLength { ld(header) }
    ld(header)
    ret(true)
    end()
}

/**
 * `s.toInt32(radix)`: optional surrounding whitespace, an optional sign,
 * then at least one digit valid in the radix. Accumulates negatively so
 * that -2^31 parses, and bubbles on anything else, overflow included.
 */
val Runtime.stringToInt: Label get() = routine("str_to_int", 2) {
    begin(5)
    val s = param(0)
    val radix = param(1)
    val i = param(2)
    val end = param(3)
    val neg = param(4)
    val acc = param(5)
    fun isSpace(at: () -> Unit) {
        // c == 32 || (9 <= c <= 13)
        codePoint(s, at)
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
    val bad = failLanding()
    // radix must be 2..36
    val radixOk = label("radix")
    ld(radix)
    e.const(1)
    e.op(Greater)
    e.const(37)
    ld(radix)
    e.op(Greater)
    e.op(Multiply)
    e.branch(radixOk, bad)
    e.startBlock(radixOk)
    st(end) { length(s) }
    e.loop(label("lead").hint, {
        ld(end)
        ld(i)
        e.op(Greater)
    }) { exit ->
        val more = label("more")
        isSpace { ld(i) }
        e.branch(more, exit)
        e.startBlock(more)
        inc(i)
    }
    e.loop(label("trail").hint, {
        ld(end)
        ld(i)
        e.op(Greater)
    }) { exit ->
        val more = label("more")
        isSpace {
            ld(end)
            e.const(1)
            e.op(Subtract)
        }
        e.branch(more, exit)
        e.startBlock(more)
        inc(end, -1)
    }
    // Something must be left before a sign is looked for, so that the read
    // below stays inside the string.
    val nonEmpty = label("nonempty")
    ld(end)
    ld(i)
    e.op(Greater)
    e.branch(nonEmpty, failLanding(e.depth - 1))
    e.startBlock(nonEmpty)
    e.ifThen(label("sign").hint, {
        codePoint(s) { ld(i) }
        e.const('-'.code)
        e.op(Subtract)
        e.op(Not)
    }) {
        st(neg) { e.const(1) }
        inc(i)
    }
    // At least one digit.
    val some = label("some")
    ld(end)
    ld(i)
    e.op(Greater)
    e.branch(some, failLanding(e.depth - 1))
    e.startBlock(some)
    whileLess("digits", { ld(i) }, { ld(end) }) {
        // d = c - '0' for 0-9, c - 'a' + 10 for a-z, c - 'A' + 10 for A-Z, else 99
        codePoint(s) { ld(i) }
        val c = param(6)
        frame.store(c)
        e.const(99)
        fun range(lo: Char, hi: Char, offset: Int) {
            // + (lo <= c <= hi) * (c - lo + offset - 99)
            ld(c)
            e.const(lo.code - 1)
            e.op(Greater)
            e.const(hi.code + 1)
            ld(c)
            e.op(Greater)
            e.op(Multiply)
            ld(c)
            e.const(lo.code - offset + 99)
            e.op(Subtract)
            e.op(Multiply)
            e.op(Add)
        }
        range('0', '9', 0)
        range('a', 'z', 10)
        range('A', 'Z', 10)
        frame.store(c)
        val digit = label("digit")
        ld(radix)
        ld(c)
        e.op(Greater)
        e.branch(digit, failLanding(e.depth - 1))
        e.startBlock(digit)
        // acc = acc * radix - d, which must stay >= -2^31
        st(acc) {
            ld(acc)
            ld(radix)
            e.op(Multiply)
            ld(c)
            e.op(Subtract)
            e.op(PietOp.Duplicate)
            e.const(-(1L shl 31) - 1)
            e.op(Greater)
            val fits = label("fits")
            e.branch(fits, failLanding(e.depth - 1))
            e.startBlock(fits)
        }
        inc(i)
    }
    // Positive results are -acc, and -(-2^31) does not fit.
    e.ifElseValue(label("negate").hint, { ld(neg) }, { ld(acc) }) {
        val fits = label("fits")
        ld(acc)
        e.const(-(1L shl 31))
        e.op(Subtract)
        e.branch(fits, failLanding(e.depth - 1))
        e.startBlock(fits)
        ld(acc)
        e.const(-1)
        e.op(Multiply)
    }
    ret(true)
    end()
}

/*
 * StringBuilder shares the list layout, holding code points; toString
 * copies them into a string.
 */

val Runtime.stringBuilderAppendBetween: Label get() {
    val add = listBuilderAdd
    return routine("sb_append_between", 4) {
        begin(0)
        val sb = param(0)
        val s = param(1)
        val a = param(2)
        val b = param(3)
        st(a) {
            ld(a)
            e.clampTop({ e.const(0) }, { length(s) })
        }
        st(b) {
            ld(b)
            e.clampTop({ ld(a) }, { length(s) })
        }
        whileLess("copy", { ld(a) }, { ld(b) }) {
            call(frame, add, 2, false) {
                ld(sb)
                codePoint(s) { ld(a) }
            }
            inc(a)
        }
        ret(false)
    }
}

val Runtime.stringBuilderAppendCodePoint: Label get() {
    val add = listBuilderAdd
    return routine("sb_append_cp", 2) {
        begin(0)
        val sb = param(0)
        val cp = param(1)
        val ok = label("ok")
        validCodePoint(cp)
        e.branch(ok, failLanding(e.depth - 1))
        e.startBlock(ok)
        call(frame, add, 2, false) {
            ld(sb)
            ld(cp)
        }
        ret(false)
        end()
    }
}

val Runtime.stringBuilderToString: Label get() = routine("sb_to_string", 1) {
    begin(2)
    val sb = param(0)
    val i = param(1)
    val header = param(2)
    st(header) {
        e.const(0)
        frame.alloc(1)
    }
    whileLess("copy", { ld(i) }, { ld(sb); frame.heapLoad(LIST_LENGTH) }) {
        ld(sb)
        frame.heapLoad(LIST_DATA)
        ld(i)
        e.op(Add)
        frame.heapLoad()
        frame.append()
        inc(i)
    }
    frame.fillLength { ld(header) }
    ld(header)
    ret(true)
}

/**
 * Digits of [n] in [radix], lower case. Built from -|n| because |-2^31|
 * does not fit in 32 bits; relies on npiet's / and % truncating.
 */
val Runtime.intToStringRadix: Label get() = routine("itoa_radix", 2) {
    begin(3)
    val n = param(0)
    val radix = param(1)
    val neg = param(2)
    val place = param(3)
    val header = param(4)
    st(neg) {
        ld(n)
        e.op(PietOp.Duplicate)
        e.const(0)
        e.op(Greater)
        // n > 0 ? -n : n
        e.const(-2)
        e.op(Multiply)
        e.const(1)
        e.op(Add)
        e.op(Multiply)
    }
    st(place) { e.const(1) }
    // while (place * radix <= -neg)
    e.whileLoop(label("place").hint, {
        ld(place)
        ld(radix)
        e.op(Multiply)
        ld(neg)
        e.const(-1)
        e.op(Multiply)
        e.op(Greater)
        e.op(Not)
    }) {
        st(place) {
            ld(place)
            ld(radix)
            e.op(Multiply)
        }
    }
    st(header) {
        e.const(0)
        frame.alloc(1)
    }
    e.ifThen(label("sign").hint, {
        e.const(0)
        ld(n)
        e.op(Greater)
    }) {
        e.const('-'.code)
        frame.append()
    }
    e.whileLoop(label("digits").hint, {
        ld(place)
        e.const(0)
        e.op(Greater)
    }) {
        // d = -((neg / place) % radix); char = d + (d > 9) * ('a' - '0' - 10) + '0'
        ld(neg)
        ld(place)
        e.op(Divide)
        ld(radix)
        e.op(Mod)
        e.const(-1)
        e.op(Multiply)
        e.op(PietOp.Duplicate)
        e.const(9)
        e.op(Greater)
        e.const('a'.code - '0'.code - 10)
        e.op(Multiply)
        e.op(Add)
        e.const('0'.code)
        e.op(Add)
        frame.append()
        st(place) {
            ld(place)
            ld(radix)
            e.op(Divide)
        }
    }
    frame.fillLength { ld(header) }
    ld(header)
    ret(true)
}
