package local.chessbridge

import kotlin.math.roundToInt

internal data class Corners(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun scaled(x: Float, y: Float) = Corners(
        (left * x).roundToInt(), (top * y).roundToInt(),
        (right * x).roundToInt(), (bottom * y).roundToInt()
    )
}

internal data class Calibration(val width: Int, val height: Int, val red: Boolean, val corners: Corners) {
    val x0 get() = corners.left
    val y0 get() = corners.top
    val x1 get() = corners.right
    val y1 get() = corners.bottom
    val side get() = if (red) 'w' else 'b'

    fun matchesSize(bitmap: android.graphics.Bitmap) = bitmap.width == width && bitmap.height == height

    fun point(index: Int): Pair<Int, Int> {
        val i = if (red) index else 89 - index
        return (x0 + (x1 - x0) * (i % 9) / 8f).roundToInt() to
            (y0 + (y1 - y0) * (i / 9) / 9f).roundToInt()
    }
}

internal fun square(file: Char, rank: Char) = (9 - rank.digitToInt()) * 9 + (file - 'a')
internal data class Board(val cells: String, val side: Char) {
    fun plausible(): Boolean {
        if (cells.length != 90 || side !in "wb" || cells.any { it !in ".KABNRCPkabnrcp" }) return false
        val limits = "kabnrcp".toList().zip(listOf(1, 2, 2, 2, 2, 2, 5))
        for (red in listOf(true, false)) {
            for ((piece, limit) in limits) {
                val label = if (red) piece.uppercaseChar() else piece
                if (cells.count { it == label } > limit) return false
            }
            val king = cells.indexOf(if (red) 'K' else 'k')
            if (king < 0 || king % 9 !in 3..5 || king / 9 !in (if (red) 7..9 else 0..2)) return false
        }
        return true
    }

    fun fen(): String = cells.chunked(9).joinToString("/") { row ->
        buildString {
            var blanks = 0
            for (piece in row + "!") {
                if (piece == '.') blanks++ else {
                    if (blanks > 0) append(blanks)
                    blanks = 0
                    if (piece != '!') append(piece)
                }
            }
        }
    } + " $side - - 0 1"

}
