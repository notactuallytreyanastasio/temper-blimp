package lang.temper.be.piet

/**
 * A grid of codels, one pixel each. A cell is [BLACK], [WHITE], or a colour
 * index `hue * 3 + lightness` with hue in red, yellow, green, cyan, blue,
 * magenta order and lightness in light, normal, dark order.
 */
class PietImage(val width: Int, val height: Int) {
    private val cells = IntArray(width * height) { BLACK }

    operator fun get(x: Int, y: Int): Int = cells[y * width + x]

    operator fun set(x: Int, y: Int, cell: Int) {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside $width x $height" }
        cells[y * width + x] = cell
    }

    /** Binary PPM (P6), which npiet reads without libpng. */
    fun toPpm(): ByteArray {
        val header = "P6\n$width $height\n255\n".encodeToByteArray()
        val out = ByteArray(header.size + cells.size * 3)
        header.copyInto(out)
        var i = header.size
        for (cell in cells) {
            val rgb = rgbOf(cell)
            out[i++] = (rgb shr 16).toByte()
            out[i++] = (rgb shr 8).toByte()
            out[i++] = rgb.toByte()
        }
        return out
    }

    companion object {
        const val BLACK = -1
        const val WHITE = -2

        fun colour(hue: Int, lightness: Int): Int = hue.mod(HUES) * LIGHTNESSES + lightness.mod(LIGHTNESSES)

        /** The colour reached from [from] by performing [op]. */
        fun next(from: Int, op: PietOp): Int =
            colour(from / LIGHTNESSES + op.hueSteps, from % LIGHTNESSES + op.darkSteps)

        /** The colour that reaches [to] by performing [op]. */
        fun previous(to: Int, op: PietOp): Int =
            colour(to / LIGHTNESSES - op.hueSteps, to % LIGHTNESSES - op.darkSteps)

        fun rgbOf(cell: Int): Int = when (cell) {
            BLACK -> 0x000000
            WHITE -> 0xFFFFFF
            else -> {
                val hue = cell / LIGHTNESSES
                val (lo, hi) = when (cell % LIGHTNESSES) {
                    0 -> 0xC0 to 0xFF
                    1 -> 0x00 to 0xFF
                    else -> 0x00 to 0xC0
                }
                // Which of R, G, B are on, per hue.
                val on = HUE_CHANNELS[hue]
                var rgb = 0
                for (channel in 0 until 3) {
                    val bit = 1 shl (2 - channel)
                    rgb = (rgb shl 8) or (if (on and bit != 0) hi else lo)
                }
                rgb
            }
        }

        const val HUES = 6
        const val LIGHTNESSES = 3

        // red, yellow, green, cyan, blue, magenta as RGB bit masks
        private val HUE_CHANNELS = intArrayOf(0b100, 0b110, 0b010, 0b011, 0b001, 0b101)
    }
}
