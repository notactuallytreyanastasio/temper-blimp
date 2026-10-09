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
 *  y=3..    .|#############.   x=0 is the express lane, x=1 the trunk
 *           LL#############.   a landing: the lane rejoins the trunk
 *           #P.body........    a row: P turns right when the count is 0
 *           .O#############.   an origin: O turns left into the lane
 *           ...
 *  y=H-1    #..............    the way home, running left to the trunk
 * ```
 *
 * Control circulates clockwise. It runs *up* the trunk testing each row in
 * turn with `dup not pointer`: pointer rotates the direction pointer by 1
 * when the count is 0, turning control right into the row; otherwise
 * `push 1 subtract` counts down and the trunk continues up. A row ends in
 * white running to the right edge. White never executes anything; a slide
 * that meets the edge rotates clockwise, so it runs down the right edge, left
 * along the bottom, and up into the foot of the trunk again.
 *
 * Counting down one row at a time makes a jump to row k cost 5k steps, and
 * a loop pays that on every iteration: with 700 rows, an average jump was
 * about 1800 steps. So every [group] rows the trunk has an origin that asks
 * whether the count is at least G. If it is, the count drops by G and control
 * turns left into the lane at x=0, a white column it slides up for free,
 * landing G rows further on. The test leaves `(count >= G) * G` on the stack
 * and hands a copy to pointer: G is chosen to be 3 more than a multiple of 4,
 * so that is a turn of three clockwise steps, which is left, or no turn at
 * all. The landing then subtracts whatever is on top: G arriving from the
 * lane, and 0 arriving from below, where `push 1 not` makes one.
 *
 * Every block is one codel thick across the direction it is crossed, so the
 * codel chooser never matters, and rows are separated by black so that blocks
 * of equal colour in neighbouring rows never merge. A landing is two codels
 * wide, and the codel above its lane half is black, so leaving it upward
 * always continues up the trunk.
 */
class DispatchLayout(private val rows: List<List<PietInstr>>, private val entry: Int) {
    val halt: Int get() = rows.size

    /** Rows per express-lane hop: about the square root, and 3 mod 4. */
    private val group: Int = run {
        var g = kotlin.math.sqrt(rows.size.toDouble()).toInt().coerceAtLeast(MIN_GROUP)
        while (g % 4 != 3) g += 1
        g
    }

    private enum class Kind { Plain, Landing, Origin, RowPointer }

    private class TrunkStep(val step: Step, val kind: Kind = Kind.Plain, val row: Int = -1)

    /**
     * The trunk from its foot up, as the steps between its blocks. Block 0
     * is the foot, entered from white; block k + 1 is what step k leads to,
     * and carries that step's kind.
     */
    private fun trunkSteps(): List<TrunkStep> = buildList {
        fun step(op: PietOp, kind: Kind = Kind.Plain, row: Int = -1, width: Int = 1) =
            add(TrunkStep(Step(op, width), kind, row))
        for (i in rows.indices) {
            if (i >= group && i % group == 0) {
                // Landing: from below, push 1 not makes the 0 to subtract.
                step(PietOp.Push, width = 1)
                step(PietOp.Not, Kind.Landing, i)
                step(PietOp.Subtract)
            }
            if (i % group == 0 && i + group < rows.size) {
                // Origin: [n] -> [n, (n >= G) * G], a copy of which is the turn.
                step(PietOp.Duplicate)
                step(PietOp.Push, width = group - 1)
                step(PietOp.Greater)
                step(PietOp.Push, width = group)
                step(PietOp.Multiply)
                step(PietOp.Duplicate)
                step(PietOp.Pointer, Kind.Origin, i + group)
                // Not taken: the 0 goes.
                step(PietOp.Pop)
            }
            step(PietOp.Duplicate)
            step(PietOp.Not)
            step(PietOp.Pointer, Kind.RowPointer, i)
            step(PietOp.Push, width = 1)
            step(PietOp.Subtract)
        }
        // Into the trap. Pop is the one command that is harmless whatever
        // the stack holds.
        step(PietOp.Pop)
    }

    fun render(): PietImage {
        val header = strip(lower(listOf(PietInstr.Const(entry.toLong()))))
        val bodies = rows.map { strip(lower(listOf(PietInstr.Op(PietOp.Pop)) + it)) }

        // Colour the trunk. Block k is as tall as the push that leaves it.
        val steps = trunkSteps()
        val heights = IntArray(steps.size + 1) { 1 }
        steps.forEachIndexed { k, s -> if (s.step.op == PietOp.Push) heights[k] = s.step.width }
        val colours = IntArray(steps.size + 1)
        colours[0] = PietImage.colour(0, 0)
        steps.forEachIndexed { k, s -> colours[k + 1] = PietImage.next(colours[k], s.step.op) }

        val trunkHeight = heights.sum()
        val width = maxOf(header.size, (bodies.maxOfOrNull { it.size } ?: 0) + BODY_X) + 1
        // Header, black row, trap row, the trunk, and the bottom row.
        val height = TRAP_Y + 1 + trunkHeight
        val image = PietImage(width, height)
        val home = width - 1
        val floor = height - 1

        header.forEachIndexed { x, c -> image[x, 0] = c }
        for (x in header.size until width) image[x, 0] = PietImage.WHITE
        for (y in 0 until height) image[home, y] = PietImage.WHITE
        for (x in TRUNK_X until width) image[x, floor] = PietImage.WHITE

        // Paint the trunk upward from just above the floor, remembering where
        // each landing, origin and row pointer ended up.
        val landingY = mutableMapOf<Int, Int>()
        val originY = mutableListOf<Pair<Int, Int>>() // y, landing row
        val rowY = IntArray(rows.size)
        var y = floor - 1
        for (k in 0..steps.size) {
            val h = heights[k]
            // A block's lowest codel is the one entered from below.
            for (dy in 0 until h) image[TRUNK_X, y - dy] = colours[k]
            if (k > 0) {
                val s = steps[k - 1]
                when (s.kind) {
                    Kind.Landing -> landingY[s.row] = y
                    Kind.Origin -> originY.add(y to s.row)
                    Kind.RowPointer -> rowY[s.row] = y
                    Kind.Plain -> {}
                }
            }
            y -= h
        }
        check(y == TRAP_Y - 1) { "trunk ends at $y" }
        // The trap: three wide, so that leaving downward, whichever way the
        // codel chooser points, picks a corner codel with black beneath it.
        val top = colours[steps.size]
        image[TRUNK_X - 1, TRAP_Y] = top
        image[TRUNK_X + 1, TRAP_Y] = top

        // The lane: white from each origin up to just below its landing.
        for ((oy, row) in originY) {
            val ly = landingY.getValue(row)
            for (yy in ly + 1..oy) image[LANE_X, yy] = PietImage.WHITE
            image[LANE_X, ly] = image[TRUNK_X, ly]
        }

        bodies.forEachIndexed { i, body ->
            val by = rowY[i]
            image[TRUNK_X + 1, by] = PietImage.WHITE
            body.forEachIndexed { x, c -> image[BODY_X + x, by] = c }
            for (x in BODY_X + body.size until width) image[x, by] = PietImage.WHITE
        }
        return image
    }

    companion object {
        private const val LANE_X = 0
        private const val TRUNK_X = 1
        private const val BODY_X = 3
        private const val TRAP_Y = 2

        /** Below this, a hop is not worth the test. */
        private const val MIN_GROUP = 7
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
