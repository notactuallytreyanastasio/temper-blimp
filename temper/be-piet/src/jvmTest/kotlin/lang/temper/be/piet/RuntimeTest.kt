package lang.temper.be.piet

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTest {
    private fun run(globals: Int = 0, body: MainWriter.(Runtime) -> Unit): String {
        val program = AsmProgram()
        val main = MainWriter(program, globals)
        val rt = Runtime(program)
        // As every program does: the intern table first, at address 0.
        call(main.frame, rt.allocZeros, 1, true) { main.e.const(INTERN_BUCKETS) }
        main.e.op(PietOp.Pop)
        main.body(rt)
        main.halt()
        return runNpiet(program.link(main.entry).render())
    }

    @Test
    fun logAStringLiteral() {
        assertEquals(
            "Hello, World!\n",
            run { rt ->
                call(frame, rt.log, 1, false) { frame.stringLiteral("Hello, World!") }
            },
        )
    }

    @Test
    fun utf8IsTheProgramsJob() {
        assertEquals(
            "é€😀\n",
            run { rt ->
                call(frame, rt.log, 1, false) { frame.stringLiteral("é€😀") }
            },
        )
    }

    @Test
    fun intToString() {
        val values = listOf(0L, 7L, -7L, 10L, 1234567L, -2147483648L, 2147483647L)
        assertEquals(
            values.joinToString("") { "$it\n" },
            run { rt ->
                for (v in values) {
                    call(frame, rt.log, 1, false) {
                        call(frame, rt.intToString, 1, true) { e.const(v) }
                    }
                }
            },
        )
    }

    @Test
    fun globalsSurviveTheHeapGrowing() {
        assertEquals(
            "42\nab\n",
            run(globals = 2) { rt ->
                e.const(42)
                frame.storeGlobal(1)
                frame.stringLiteral("ab")
                frame.storeGlobal(0)
                frame.stringLiteral("ignored")
                e.op(PietOp.Pop)
                call(frame, rt.log, 1, false) {
                    call(frame, rt.intToString, 1, true) { frame.loadGlobal(1) }
                }
                call(frame, rt.log, 1, false) { frame.loadGlobal(0) }
            },
        )
    }
}
