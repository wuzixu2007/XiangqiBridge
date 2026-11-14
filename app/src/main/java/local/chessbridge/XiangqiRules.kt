package local.chessbridge

import kotlin.math.abs
import kotlin.math.sign

internal object XiangqiRules {
    private fun color(piece: Char) = when {
        piece == '.' -> '-'
        piece.isUpperCase() -> 'w'
        else -> 'b'
    }

    private fun opposite(side: Char) = if (side == 'w') 'b' else 'w'
    private fun ownHalf(y: Int, side: Char) = if (side == 'w') y >= 5 else y <= 4
    private fun palace(x: Int, y: Int, side: Char) = x in 3..5 && if (side == 'w') y >= 7 else y <= 2

    private fun attacks(cells: String, from: Int, to: Int): Boolean {
        if (from == to) return false
        val piece = cells[from]
        val side = color(piece)
        val x = from % 9
        val y = from / 9
        val tx = to % 9
        val ty = to / 9
        val dx = tx - x
        val dy = ty - y
        val ax = abs(dx)
        val ay = abs(dy)
        val straight = dx == 0 || dy == 0
        val step = dx.sign + dy.sign * 9
        val between = if (straight) (1 until maxOf(ax, ay)).count { cells[from + step * it] != '.' } else 0
        return when (piece.lowercaseChar()) {
            'r' -> straight && between == 0
            'c' -> straight && between == if (cells[to] == '.') 0 else 1
            'n' -> ((ax == 2 && ay == 1) || (ax == 1 && ay == 2)) &&
                cells[from + (if (ax == 2) dx.sign else dy.sign * 9)] == '.'
            'b' -> ax == 2 && ay == 2 && ownHalf(ty, side) && cells[from + dx / 2 + dy / 2 * 9] == '.'
            'a' -> ax == 1 && ay == 1 && palace(tx, ty, side)
            'k' -> (ax + ay == 1 && palace(tx, ty, side)) ||
                (dx == 0 && between == 0 && cells[to].lowercaseChar() == 'k')
            'p' -> (dx == 0 && dy == if (side == 'w') -1 else 1) ||
                (!ownHalf(y, side) && dy == 0 && ax == 1)
            else -> false
        }
    }

    private fun checked(board: Board, side: Char): Boolean {
        val general = board.cells.indexOf(if (side == 'w') 'K' else 'k')
        return general < 0 || board.cells.indices.any { from ->
            color(board.cells[from]) == opposite(side) && attacks(board.cells, from, general)
        }
    }

    fun validSeed(board: Board) = board.plausible() && !checked(board, opposite(board.side))

    fun apply(board: Board, move: String): Board? {
        if (!move.matches(Regex("[a-i][0-9][a-i][0-9]"))) return null
        return apply(board, square(move[0], move[1]), square(move[2], move[3]))
    }

    private fun apply(board: Board, from: Int, to: Int): Board? {
        val cells = board.cells
        if (color(cells[from]) != board.side || color(cells[to]) == board.side ||
            cells[to].lowercaseChar() == 'k' || !attacks(cells, from, to)) return null
        val next = Board(String(cells.toCharArray().apply {
            this[to] = this[from]
            this[from] = '.'
        }), opposite(board.side))
        return next.takeUnless { checked(it, board.side) }
    }

    fun reconcile(board: Board, observed: String): Board? {
        if (board.cells == observed) return board
        val changed = board.cells.indices.filter { board.cells[it] != observed[it] }
        if (changed.size != 2) return null
        val from = changed.singleOrNull { observed[it] == '.' } ?: return null
        val to = changed.first { it != from }
        return apply(board, from, to)?.takeIf { it.cells == observed }
    }

}
