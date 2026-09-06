package com.hardlinelabs.relay.atak

import org.junit.Assert.*
import org.junit.Test

class PliTest {
    private val p = Pli(42, 100_000, 40.1234567, -75.1234567, 10, "HLM1")
    @Test fun wireRoundTrip() { assertEquals(PliWire.Position(p), PliWire.decode(PliWire.encode(p))) }
    @Test fun receiptRoundTrip() { assertEquals(PliWire.Receipt(42), PliWire.decode(PliWire.ack(42))) }
    @Test fun malformedRejected() {
        for (bytes in listOf(byteArrayOf(), PliWire.ack(42) + 0, PliWire.encode(p).dropLast(1).toByteArray(), ByteArray(80))) {
            assertThrows(IllegalArgumentException::class.java) { PliWire.decode(bytes) }
        }
    }
    @Test fun invalidCoordinatesRejected() {
        assertThrows(IllegalArgumentException::class.java) { PliWire.encode(p.copy(lat = Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { PliWire.encode(p.copy(lon = 181.0)) }
        assertThrows(IllegalArgumentException::class.java) { PliWire.encode(p.copy(interval = 1)) }
    }
    @Test fun duplicatesDoNotRefresh() {
        val s = PliState()
        assertTrue(s.accept("peer", p, 0, 100_000))
        assertFalse(s.accept("peer", p, 50_000, 150_000))
        assertTrue(s.peers.getValue("peer").stale(60_000))
    }
    @Test fun lateAndFutureFixesRejected() {
        val s = PliState()
        assertFalse(s.accept("peer", p, 0, 300_000))
        assertFalse(s.accept("peer", p.copy(id = 43), 0, 1))
    }
    @Test fun olderFixCannotMoveMarkerBackwards() {
        val s = PliState()
        assertTrue(s.accept("peer", p, 0, 100_000))
        assertFalse(s.accept("peer", p.copy(id = 43, fixTime = 90_000), 1, 100_000))
    }
    @Test fun fakeTwoWayTransportAndNoAckLoop() {
        val a = PliState(); val b = PliState()
        a.sent(p.id, 0)
        val received = (PliWire.decode(PliWire.encode(p)) as PliWire.Position).pli
        assertTrue(b.accept("a", received, 1000, 101_000))
        val ack = PliWire.decode(PliWire.ack(received.id)) as PliWire.Receipt
        assertTrue(a.receipt(ack.id, "b", 2000))
        assertFalse(a.receipt(ack.id, "b", 3000))
        assertFalse(a.receipt(900, "b", 3000))
        assertEquals(setOf("b"), a.pending.getValue(p.id).receipts)
        assertTrue(a.peers.isEmpty()) // Receipt is not peer position or a new receipt request.
    }
    @Test fun freshnessUsesMonotonicTime() {
        val s = PliState(); s.accept("b", p.copy(interval = 30), 1000, 100_000)
        assertFalse(s.peers.getValue("b").stale(90_999))
        assertTrue(s.peers.getValue("b").stale(91_000))
        assertEquals(90, s.peers.getValue("b").age(91_000))
    }
    @Test fun historyBoundedAndResettable() {
        val s = PliState()
        repeat(100) { s.sent(it + 1L, 0); s.accept("p$it", p.copy(id = it + 1L), 0, 100_000) }
        assertEquals(32, s.pending.size); assertEquals(32, s.peers.size)
        s.expire(300_001); assertTrue(s.pending.isEmpty())
        s.clear(); assertTrue(s.peers.isEmpty())
    }
}
