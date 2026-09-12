package com.hardlinelabs.relay.atak

import org.junit.Assert.*
import org.junit.Test

class MeshHealthTest {
    @Test
    fun connectionIsNotEvidenceOfMeshContact() {
        assertEquals(MeshHealth.State.FAULT, MeshHealth.assess(false, true, true, false))
        assertEquals(MeshHealth.State.INACTIVE, MeshHealth.assess(true, false, true, false))
        assertEquals(MeshHealth.State.WAITING, MeshHealth.assess(true, true, false, false))
        assertEquals(MeshHealth.State.WAITING, MeshHealth.assess(true, true, true, true))
        assertEquals(MeshHealth.State.CONFIRMED, MeshHealth.assess(true, true, true, false))
    }

    @Test
    fun intervalsRoundTripAndLongIntervalStillBecomesStale() {
        PliWire.intervals.forEach { seconds ->
            val p = Pli(1, 10_000, 1.0, 2.0, seconds, "Team")
            assertEquals(p, (PliWire.decode(PliWire.encode(p)) as PliWire.Position).pli)
            val peer = PliState.Peer(p, 0, 10_000)
            assertFalse(peer.stale(maxOf(60_000L, seconds * 3000L) - 1))
            assertTrue(peer.stale(maxOf(60_000L, seconds * 3000L)))
        }
    }

    @Test
    fun receiptsCoalesceAndRespectGlobalAndPeerBudgets() {
        val s = ReceiptSchedule()
        fun pli(id: Long, interval: Int = 10) = Pli(id, 1000, 1.0, 2.0, interval, "T")
        s.enqueue("a", pli(1), 0, 500)
        assertNull(s.poll(499))
        assertEquals(1L, s.poll(500))
        s.enqueue("a", pli(2), 1000, 500)
        s.enqueue("a", pli(3), 2000, 500)
        s.enqueue("b", pli(4), 2000, 500)
        assertNull(s.poll(5499))
        assertEquals(4L, s.poll(5500))
        assertNull(s.poll(60_499))
        assertEquals(3L, s.poll(60_500))
        s.enqueue("a", pli(5, 0), 61_000, 500)
        assertEquals(5L, s.poll(65_500))
        s.enqueue("b", pli(6), 66_000, 500)
        s.clear()
        assertNull(s.poll(99_000))
    }
}
