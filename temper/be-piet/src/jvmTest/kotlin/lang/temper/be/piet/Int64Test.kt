package lang.temper.be.piet

import kotlin.test.Test
import kotlin.test.assertEquals

class Int64Test {
    @Test
    fun packThenUnpackKeepsAllSixtyFourBits() {
        val values =
            listOf(0L, 1L, -1L, 2147483647L, 2147483648L, -2147483649L, 4294967296L, Long.MAX_VALUE, -Long.MAX_VALUE)
        val program = AsmProgram()
        val main = MainWriter(program, 0)
        for (v in values) {
            main.e.const(v)
            main.frame.pack64()
            main.frame.unpack64()
            main.e.op(PietOp.OutNumber)
            main.e.const(' '.code)
            main.e.op(PietOp.OutChar)
        }
        main.halt()
        assertEquals(values.joinToString(" ", postfix = " "), runNpiet(program.link(main.entry).render()))
    }

    @Test
    fun toStringInAnyRadix() {
        val values = listOf(0L, 7L, -7L, 2147483648L, Long.MAX_VALUE, -Long.MAX_VALUE - 1)
        val program = AsmProgram()
        val main = MainWriter(program, 0)
        val rt = Runtime(program)
        call(main.frame, rt.allocZeros, 1, true) { main.e.const(INTERN_BUCKETS) }
        main.e.op(PietOp.Pop)
        for (v in values) {
            for (radix in listOf(10, 16)) {
                call(main.frame, rt.log, 1, false) {
                    call(main.frame, rt.int64ToString, 2, true) {
                        if (v == Long.MIN_VALUE) main.e.constMin64() else main.e.const(v)
                        main.frame.pack64()
                        main.e.const(radix)
                    }
                }
            }
        }
        main.halt()
        val want = values.joinToString("") { "${it.toString(10)}\n${it.toString(16)}\n" }
        assertEquals(want, runNpiet(program.link(main.entry).render()))
    }
}
