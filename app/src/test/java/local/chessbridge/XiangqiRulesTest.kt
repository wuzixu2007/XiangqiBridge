package local.chessbridge

import org.junit.Assert.*
import org.junit.Test

class XiangqiRulesTest {
    private val opening = Board("rnbakabnr........." +
        ".c.....c.p.p.p.p.p..................P.P.P.P.P.C.....C." +
        ".........RNBAKABNR", 'w')

    @Test fun openingAndFastReply() {
        assertTrue(XiangqiRules.validSeed(opening))
        val own = checkNotNull(XiangqiRules.apply(opening, "h2e2"))
        val reply = checkNotNull(XiangqiRules.apply(own, "h9g7"))
        assertEquals(own, XiangqiRules.reconcile(own, own.cells))
        assertEquals(reply, XiangqiRules.reconcile(own, reply.cells))
        assertNull(XiangqiRules.reconcile(opening, reply.cells))
    }

    @Test fun capturesWithIdenticalOccupancyAreDistinguishedByPieceIdentity() {
        val board = Board(listOf("rnbaka...", "........r", "....b...n", "p.p.p.p.p", ".........",
            ".........", "P.P.P.P.P", "....C..C.", ".........", ".NBAKABR.").joinToString(""), 'w')
        assertTrue(board.plausible())
        val redRook = board.cells.toCharArray().apply { this[square('b', '8')] = 'R' }
        val before = board.copy(cells = String(redRook))
        val first = checkNotNull(XiangqiRules.apply(before, "b8b9"))
        val second = checkNotNull(XiangqiRules.apply(before, "b8i8"))
        assertEquals(first.cells.map { it == '.' }, second.cells.map { it == '.' })
        assertEquals(first, XiangqiRules.reconcile(before, first.cells))
        assertEquals(second, XiangqiRules.reconcile(before, second.cells))
    }

    @Test fun illegalMovesAndInvalidSeedsAreRejected() {
        assertNull(XiangqiRules.apply(opening, "e0e3"))
        assertNull(XiangqiRules.apply(opening, "a0a4"))
        assertFalse(opening.copy(cells = opening.cells.replace('K', '.')).plausible())
    }
}
