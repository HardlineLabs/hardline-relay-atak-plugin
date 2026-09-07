package com.hardlinelabs.relay.atak

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PointTest {
    private val p = MeshPoint(7, 9, 100_000, 2, 40.1234567, -75.1234567, 0, -65536, "Rally 1")

    @Test fun boundedPointAndReceiptRoundTrip() {
        val bytes = PointWire.encode(p)
        assertEquals(54, bytes.size)
        assertEquals(PointWire.Point(p), PointWire.decode(bytes))
        assertEquals(71, PointWire.encode(p.copy(name = "x".repeat(24))).size)
        assertEquals(17, PointWire.receipt(7, 1).size)
        assertEquals(PointWire.Receipt(7, 1), PointWire.decode(PointWire.receipt(7, 1)))
        assertFalse(PointWire.isPointMessage(PliWire.ack(7)))
        assertTrue(PointWire.isPointMessage(bytes))
    }

    @Test fun wireRejectsInvalidAndOversizedInputs() {
        for (bad in listOf(p.copy(lat = Double.NaN), p.copy(lon = 181.0), p.copy(name = "x".repeat(25)),
            p.copy(name = " "), p.copy(name = "a\nb"), p.copy(name = "é".repeat(13)),
            p.copy(name = "\uD800"), p.copy(symbol = 255), p.copy(recipient = 0xffffffffL),
            p.copy(token = 0), p.copy(id = 0), p.copy(revision = 0))) {
            assertThrows(Exception::class.java) { PointWire.encode(bad) }
        }
        val bytes = PointWire.encode(p)
        for (bad in listOf(bytes + 0, bytes.dropLast(1).toByteArray(), ByteArray(100),
            PointWire.receipt(7, 1) + 0, bytes.clone().apply { this[47] = 0xff.toByte() },
            bytes.clone().apply { this[4] = 99 }, bytes.clone().apply { this[0] = 0 })) {
            assertThrows(Exception::class.java) { PointWire.decode(bad) }
        }
        for (size in bytes.indices) assertThrows(Exception::class.java) { PointWire.decode(bytes.copyOf(size)) }
    }

    @Test fun unicodeCoordinatesAndUnsignedNodeIds() {
        val edge = p.copy(lat = -90.0, lon = 180.0, recipient = 0xf6fcc914L, name = "é".repeat(12))
        assertEquals(PointWire.Point(edge), PointWire.decode(PointWire.encode(edge)))
        assertEquals(0xf6fcc914L, PointWire.nodeId("!f6fcc914"))
        assertThrows(IllegalArgumentException::class.java) { PointWire.nodeId("!ffffffff") }
        assertEquals(PointWire.pointId("marker-1"), PointWire.pointId("marker-1"))
        assertNotEquals(PointWire.pointId("marker-1"), PointWire.pointId("marker-2"))
        assertEquals(0x48525031, ByteBuffer.wrap(PointWire.encode(p)).int)
    }

    @Test fun onlyAddressedRecipientImportsAndOnlyIntendedPeerConfirms() {
        val sender = PointSendState(); val receiver = PointReceiveState()
        sender.begin(p, "Peer B", 0)
        assertEquals(PointReceiveState.Decision.IGNORE, receiver.decide(1, 3, p, 100))
        assertEquals(PointReceiveState.Decision.IMPORT, receiver.decide(1, 2, p, 100))
        receiver.committed(1, p, 100) // Host calls only after creating the marker.
        val ack = PointWire.decode(PointWire.receipt(p.token, 1)) as PointWire.Receipt
        assertFalse(sender.receipt(ack.token, 3, 200))
        assertFalse(sender.receipt(99, 2, 200))
        assertTrue(sender.receipt(ack.token, 2, 200))
        sender.submitted(p.token) // Receipt racing the worker callback must remain received.
        sender.failed(p.token, "late worker failure")
        assertEquals(PointSendState.Status.RECEIVED, sender.status)
    }

    @Test fun lastAttemptCannotBeOverwrittenByEarlierCallbacks() {
        val s = PointSendState(); s.begin(p, "B", 0)
        s.begin(p.copy(token = 8), "B", 1000)
        s.submitted(7); s.failed(7, "old failure")
        assertFalse(s.receipt(7, 2, 2000))
        assertEquals(1, s.roundTrips.count)
        assertEquals(2000L, s.roundTrips.average)
        assertFalse(s.receipt(7, 2, 2100))
        assertEquals(1, s.roundTrips.count)
        assertEquals(PointSendState.Status.SUBMITTING, s.status)
        s.submitted(8); assertEquals(PointSendState.Status.AWAITING, s.status)
        assertTrue(s.receipt(8, 2, 4000))
        assertEquals(2, s.roundTrips.count)
        assertEquals(2500L, s.roundTrips.average)
        s.interrupted(); assertEquals(0, s.roundTrips.count)
    }

    @Test fun timeoutIsUnknownAndLateReceiptCanConfirmWithinHistory() {
        val s = PointSendState(); s.begin(p, "B", 0); s.submitted(7)
        s.tick(59_999); assertEquals(PointSendState.Status.AWAITING, s.status)
        s.tick(60_000); assertEquals(PointSendState.Status.UNCONFIRMED, s.status)
        assertTrue(s.receipt(7, 2, 120_000))
        s.tick(600_000); assertEquals(PointSendState.Status.RECEIVED, s.status)
        s.begin(p.copy(token = 8), "B", 0)
        assertFalse(s.receipt(8, 2, 300_001))
    }

    @Test fun resetDoesNotClaimLossOrEraseConfirmedResult() {
        val s = PointSendState(); s.begin(p, "B", 0); s.interrupted()
        assertEquals(PointSendState.Status.UNCONFIRMED, s.status)
        assertFalse(s.receipt(7, 2, 100))
        s.begin(p.copy(token = 8), "B", 100)
        assertTrue(s.receipt(8, 2, 200))
        s.interrupted(); assertEquals(PointSendState.Status.RECEIVED, s.status)
        s.failed(null, "Unsupported selection")
        assertEquals(PointSendState.Status.FAILED, s.status)
        assertNull(s.point)
        assertFalse(s.receipt(7, 2, 200))
        s.clear(); assertEquals(PointSendState.Status.NONE, s.status)
    }

    @Test fun duplicateCanRecoverLostReceiptWithoutReimporting() {
        val r = PointReceiveState()
        assertEquals(PointReceiveState.Decision.IMPORT, r.decide(1, 2, p, 0))
        // Failed host import has not committed, so another attempt must still import.
        assertEquals(PointReceiveState.Decision.IMPORT, r.decide(1, 2, p, 100))
        r.committed(1, p, 100)
        assertEquals(PointReceiveState.Decision.IGNORE, r.decide(1, 2, p, 200))
        assertEquals(PointReceiveState.Decision.RECEIPT_ONLY, r.decide(1, 2, p, 5100))
        assertEquals(PointReceiveState.Decision.RECEIPT_ONLY, r.decide(1, 2, p.copy(token = 8), 5100))
    }

    @Test fun delayedUpdatesNeverMovePointBackwards() {
        val r = PointReceiveState(); r.committed(1, p, 0)
        assertEquals(PointReceiveState.Decision.IGNORE, r.decide(1, 2, p.copy(revision = 99_999), 10_000))
        assertEquals(PointReceiveState.Decision.IGNORE, r.decide(1, 2, p.copy(lat = 41.0), 10_000))
        assertEquals(PointReceiveState.Decision.IMPORT, r.decide(1, 2, p.copy(revision = 100_001, lat = 41.0), 10_000))
    }

    @Test fun receiveHistoryHasAHardBoundAndIndependentSenders() {
        val r = PointReceiveState()
        repeat(128) { r.committed(1, p.copy(id = it + 1L), 0) }
        assertEquals(PointReceiveState.Decision.IGNORE, r.decide(1, 2, p.copy(id = 129), 10_000))
        assertEquals(PointReceiveState.Decision.IMPORT, r.decide(1, 2, p.copy(revision = 100_001), 10_000))
        r.clear()
        assertEquals(PointReceiveState.Decision.IMPORT, r.decide(1, 2, p, 10_000))
        r.committed(1, p, 0)
        assertEquals(PointReceiveState.Decision.IMPORT, r.decide(3, 2, p, 10_000))
    }
}
