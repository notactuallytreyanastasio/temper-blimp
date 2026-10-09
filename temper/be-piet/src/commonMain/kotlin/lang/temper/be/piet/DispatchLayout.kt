package lang.temper.be.piet

/**
 * Lays a program out as one dispatch loop, so that control flow never has to
 * be drawn: every jump is a number left on the stack.
 *
 * A program is a list of rows. Row `i` runs when the value on top of the stack
 * is `i` as control reaches the trunk; it is entered with that value popped,
 * and must finish by leaving the index of the next row on top. Leaving
 * [halt] (any index past the last row) stops the program.
 *
 * ```
 *  y=0      S########......   header: S pushes the entry row, then white
 *  y=1      ###############.   black, except the return column on the right
 *  y=2      TTT############.   the trap: an exhausted trunk ends here
 *  y=3..    #|#############.
 *           #P.body........    row n-1: P turns right when the count is 0
 *           #|#############.
 *           ...
 *           #P.body........    row 0
 *           #|#############.
 *  y=H-1    #..............    the way home, running left to the trunk
 * ```
 *
 * Control circulates clockwise. It runs *up* the trunk (x=1) testing each row
 * in turn with `dup not pointer`: pointer rotates the direction pointer by 1
 * when the count is 0, turning control right into the row; otherwise
 * `push 1 subtract` counts down and the trunk continues up. A row ends in
 * white running to the right edge. White never executes anything; a slide
 * that meets the edge rotates clockwise, so it runs down the right edge, left
 * along the bottom, and up into the foot of the trunk again.
 *
 * Every block is one codel thick across the direction it is crossed, so the
 * codel chooser never matters, and rows are separated by black so that blocks
 * of equal colour in neighbouring rows never merge.
 */
class DispatchLayout(private val rows: List<List<PietInstr>>, private val entry: Int) {
    val halt: Int get() = rows.size

    fun render(): PietImage {
        val header = strip(lower(listOf(PietInstr.Const(entry.toLong()))))
        val bodies = rows.map { strip(lower(listOf(PietInstr.Op(PietOp.Pop)) + it)) }
        val width = maxOf(header.size, (bodies.maxOfOrNull { it.size } ?: 0) + BODY_X) + 1
        val height = ROW_PITCH * rows.size + ROW_PITCH
        val image = PietImage(width, height)
        val home = width - 1
        val floor = height - 1

        header.forEachIndexed { x, c -> image[x, 0] = c }
        for (x in header.size until width) image[x, 0] = PietImage.WHITE
        for (y in 0 until height) image[home, y] = PietImage.WHITE
        for (x in TRUNK_X until width) image[x, floor] = PietImage.WHITE

        // The trunk, painted from its foot upward.
        val trunkSteps = buildList {
            repeat(rows.size) {
                add(Step(PietOp.Duplicate))
                add(Step(PietOp.Not))
                add(Step(PietOp.Pointer))
                add(Step(PietOp.Push, 1))
                add(Step(PietOp.Subtract))
            }
            // Into the trap. Pop is the one command that is harmless whatever
            // the stack holds.
            add(Step(PietOp.Pop))
        }
        val trunk = strip(trunkSteps)
        trunk.forEachIndexed { i, c -> image[TRUNK_X, floor - 1 - i] = c }
        check(floor - trunk.size == TRAP_Y)

        // A trap is a block every exit from which is black or the edge. It is
        // three wide so that leaving downward, whichever way the codel chooser
        // points, picks a corner codel with black beneath it, not the trunk.
        image[TRUNK_X - 1, TRAP_Y] = image[TRUNK_X, TRAP_Y]
        image[TRUNK_X + 1, TRAP_Y] = image[TRUNK_X, TRAP_Y]

        bodies.forEachIndexed { i, body ->
            val y = rowY(i, height)
            image[TRUNK_X + 1, y] = PietImage.WHITE
            body.forEachIndexed { x, c -> image[BODY_X + x, y] = c }
            for (x in BODY_X + body.size until width) image[x, y] = PietImage.WHITE
        }
        return image
    }

    companion object {
        private const val TRUNK_X = 1
        private const val BODY_X = 3
        private const val TRAP_Y = 2
        private const val ROW_PITCH = 5

        /** Rows are numbered from the bottom, in the order the trunk tests them. */
        internal fun rowY(i: Int, height: Int) = height - ROW_PITCH - ROW_PITCH * i
    }
}

/**
 * Colours [steps] codel by codel, starting from light red. Block `i` is the
 * one step `i` leaves; a push needs it to be as wide as the value pushed.
 */
internal fun strip(steps: List<Step>): List<Int> = buildList {
    var colour = PietImage.colour(0, 0)
    for (step in steps) {
        val width = if (step.op == PietOp.Push) step.width else 1
        repeat(width) { add(colour) }
        colour = PietImage.next(colour, step.op)
    }
    add(colour)
}
