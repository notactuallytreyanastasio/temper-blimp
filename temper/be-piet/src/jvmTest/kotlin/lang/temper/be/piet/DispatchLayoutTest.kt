package lang.temper.be.piet

import lang.temper.be.piet.PietOp.Duplicate
import lang.temper.be.piet.PietOp.Greater
import lang.temper.be.piet.PietOp.OutChar
import lang.temper.be.piet.PietOp.OutNumber
import lang.temper.be.piet.PietOp.Pop
import lang.temper.be.piet.PietOp.Roll
import lang.temper.be.piet.PietOp.Subtract
import kotlin.test.Test
import kotlin.test.assertEquals

class DispatchLayoutTest {
    private fun row(vararg parts: Any): List<PietInstr> = parts.map {
        when (it) {
            is PietOp -> PietInstr.Op(it)
            is Int -> PietInstr.Const(it.toLong())
            is Long -> PietInstr.Const(it)
            else -> error(it)
        }
    }

    @Test
    fun aLoopIsARowThatNamesItself() {
        val swap = arrayOf<Any>(2, 1, Roll)
        val layout = DispatchLayout(
            listOf(
                row(3, 1), // n = 3; goto 1
                // print n; n -= 1; goto (n > 0 ? 1 : 2), computed as 2 - (n > 0)
                row(Duplicate, OutNumber, 1, Subtract, Duplicate, 0, Greater, 2, *swap, Subtract),
                row(Pop, 10, OutChar, 3),
            ),
            entry = 0,
        )
        assertEquals("321\n", runNpiet(layout.render()))
    }

    @Test
    fun entryNeedNotBeTheFirstRow() {
        val layout = DispatchLayout(
            listOf(row('a'.code, OutChar, 2), row('b'.code, OutChar, 0), row('c'.code, OutChar, 3)),
            entry = 1,
        )
        assertEquals("bac", runNpiet(layout.render()))
    }

    @Test
    fun constantsOfEverySize() {
        val values = listOf(0L, 1L, 12L, 13L, 99L, 1000L, -1L, -77L, 123456789L, 9_000_000_000L)
        val body = values.flatMap { row(it, OutNumber, ' '.code, OutChar) } + row(1)
        assertEquals(values.joinToString(" ", postfix = " "), runNpiet(DispatchLayout(listOf(body), 0).render()))
    }
}
