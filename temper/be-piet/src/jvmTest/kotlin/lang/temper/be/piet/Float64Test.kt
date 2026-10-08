package lang.temper.be.piet

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Float64Test {
    private val specials = listOf(
        0.0, -0.0, 1.0, -1.0, 0.1, 0.2, 0.3, 1.0 / 3, 2.0, 3.0, 0.5, 1e25, 1e-300,
        Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, 4.9e-324 * 3, 2.2250738585072014E-308,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 99999999999999.0, 999999999999.0,
        1.0000000000000002, 0.9999999999999999, 123456789.123, -7.5e-310,
    )

    private fun samples(n: Int): List<Pair<Double, Double>> {
        val rnd = Random(42)
        val out = mutableListOf<Pair<Double, Double>>()
        for (a in specials.take(12)) for (b in specials.shuffled(rnd).take(4)) out += a to b
        repeat(n) {
            fun one() = when (rnd.nextInt(3)) {
                0 -> Double.fromBits(rnd.nextLong())
                1 -> rnd.nextDouble(-1e6, 1e6)
                else -> specials[rnd.nextInt(specials.size)]
            }
            out += one() to one()
        }
        return out
    }

    /** NaN results compare as any NaN; everything else bit for bit. */
    private fun norm(bits: Long): Long {
        val d = Double.fromBits(bits)
        return if (d.isNaN()) 0x7FF8000000000000L else bits
    }

    private fun check(name: String, routine: (Runtime) -> Label, op: (Double, Double) -> Double) {
        val pairs = samples(60)
        val program = AsmProgram()
        val main = MainWriter(program, 0)
        val rt = Runtime(program)
        call(main.frame, rt.allocZeros, 1, true) { main.e.const(INTERN_BUCKETS) }
        main.e.op(PietOp.Pop)
        val target = routine(rt)
        for ((a, b) in pairs) {
            call(main.frame, target, 2, true) {
                main.e.const(a.toRawBits())
                main.frame.pack64()
                main.e.const(b.toRawBits())
                main.frame.pack64()
            }
            main.frame.unpack64()
            main.e.op(PietOp.OutNumber)
            main.e.const(' '.code)
            main.e.op(PietOp.OutChar)
        }
        main.halt()
        val got = runNpiet(program.link(main.entry).render()).trim().split(" ").map { norm(it.toLong()) }
        val want = pairs.map { (a, b) -> norm(op(a, b).toRawBits()) }
        val wrong = pairs.indices.filter { got.getOrNull(it) != want[it] }.map {
            val (a, b) = pairs[it]
            "$a $name $b: want ${Double.fromBits(want[it])}, got ${got.getOrNull(it)?.let(Double::fromBits)}"
        }
        assertEquals(listOf<String>(), wrong)
    }

    @Test fun add() = check("+", { it.floatAdd }) { a, b -> a + b }

    @Test fun mul() = check("*", { it.floatMul }) { a, b -> a * b }

    @Test fun div() = check("/", { it.floatDiv }) { a, b -> a / b }

    /** Temper's format: JavaScript's Number.prototype.toString, plus ".0" where it has no '.' or 'e'. */
    private fun temperFormat(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        // JDK 21's shortest digits, then JS layout. The JDK keeps one digit
        // more than shortest for the smallest subnormal, 4.9E-324, where
        // JavaScript prints 5e-324.
        val jdk = if (kotlin.math.abs(d) == Double.MIN_VALUE) "${if (d < 0) "-" else ""}5e-324" else d.toString()
        val bd = java.math.BigDecimal(jdk).stripTrailingZeros()
        val digits = bd.unscaledValue().abs().toString()
        val k = digits.length - bd.scale() // value = 0.digits * 10^k
        val n = digits.length
        val sign = if (d < 0) "-" else ""
        val body = when {
            k in n..21 -> digits + "0".repeat(k - n) + ".0"
            k in 1..21 -> digits.substring(0, k) + "." + digits.substring(k)
            k in -5..0 -> "0." + "0".repeat(-k) + digits
            else -> {
                val exp = k - 1
                digits.substring(0, 1) + "." + (if (n > 1) digits.substring(1) else "0") +
                    "e" + (if (exp >= 0) "+" else "-") + kotlin.math.abs(exp)
            }
        }
        return sign + body
    }

    @Test
    fun toStringIsShortestRoundTrip() {
        val rnd = Random(7)
        // Every value here prints in well under a second, except the extreme
        // exponents, which need bignums of thirty-odd limbs: two of those.
        val values = listOf(
            0.0, -0.0, 1.0, -1.0, 0.1, 0.3, 1.0 / 3, 2.0 / 3, 0.5, 4.0, 100.0, 1234.5, 0.000123,
            1e21, 1e20, 123e-7, 1e-7, 1e-6, 9.9999999999899e+25, 1.0000000000000002, 0.9999999999999999,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 5e-324, 1e-300,
        ) + List(6) { rnd.nextDouble(-1000.0, 1000.0) }
        val program = AsmProgram()
        val main = MainWriter(program, 0)
        val rt = Runtime(program)
        call(main.frame, rt.allocZeros, 1, true) { main.e.const(INTERN_BUCKETS) }
        main.e.op(PietOp.Pop)
        for (v in values) {
            call(main.frame, rt.log, 1, false) {
                call(main.frame, rt.floatToString, 1, true) {
                    main.e.const(v.toRawBits())
                    main.frame.pack64()
                }
            }
        }
        main.halt()
        val got = runNpiet(program.link(main.entry).render()).lines()
        val wrong = values.indices.filter { got.getOrNull(it) != temperFormat(values[it]) }.map {
            "${values[it]}: want ${temperFormat(values[it])}, got ${got.getOrNull(it)}"
        }
        assertEquals(listOf<String>(), wrong)
    }

    @Test
    fun parseRoundsCorrectly() {
        val inputs = listOf(
            "-1.5", "-0", "2.0", "2", " 2 ", "5e-1", "NaN", "-Infinity", "Infinity", "0.1", "0.3",
            "123456789012345678901234567890", "1e-300", "2.2250738585072014e-308", "4.9e-324",
            "1e400", "1.7976931348623157e308", "0.30000000000000004", "9007199254740993",
            "18014398509481985", "1E22", "1e23", "3.14159265358979323846264338327950288",
            "0.000001", "7e-10", "2.5e-324", "123.456e-2",
        )
        val program = AsmProgram()
        val main = MainWriter(program, 0)
        val rt = Runtime(program)
        call(main.frame, rt.allocZeros, 1, true) { main.e.const(INTERN_BUCKETS) }
        main.e.op(PietOp.Pop)
        for (text in inputs) {
            call(main.frame, rt.floatParse, 1, true) { main.frame.stringLiteral(text) }
            main.frame.unpack64()
            main.e.op(PietOp.OutNumber)
            main.e.const(' '.code)
            main.e.op(PietOp.OutChar)
        }
        main.halt()
        val got = runNpiet(program.link(main.entry).render()).trim().split(" ").map { norm(it.toLong()) }
        val want = inputs.map { norm(it.trim().toDouble().toRawBits()) }
        val wrong = inputs.indices.filter { got.getOrNull(it) != want[it] }.map {
            "${inputs[it]}: want ${Double.fromBits(want[it])}, got ${got.getOrNull(it)?.let(Double::fromBits)}"
        }
        assertEquals(listOf<String>(), wrong)
    }
}
