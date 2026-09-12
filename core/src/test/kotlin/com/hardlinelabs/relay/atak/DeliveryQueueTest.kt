package com.hardlinelabs.relay.atak

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class DeliveryQueueTest {
    private val payload = byteArrayOf(1, 2, 3)

    @Test
    fun firstPacketLostRecoversAndStopsAfterReceipt() {
        val q = DeliveryQueue { 0 }
        q.offer("pli:1", payload, null, 0)
        assertEquals(1, q.poll(0)!!.attempt) // lost
        assertNull(q.poll(89_999))
        assertEquals(2, q.poll(90_000)!!.attempt)
        assertTrue(q.acknowledge("pli:1", "peer"))
        assertNull(q.poll(400_000))
        assertEquals(1, q.retries)
    }

    @Test
    fun lostReceiptDuplicateCanBeReacknowledgedWithoutRefreshingPosition() {
        val receiver = PliState()
        val p = Pli(1, 100_000, 10.0, 20.0, 0, "A")
        assertTrue(receiver.accept("sender", p, 0, 100_000))
        assertFalse(receiver.accept("sender", p, 90_000, 190_000))
        assertEquals(PliState.Reception.DUPLICATE, receiver.lastReception)
        assertEquals(0L, receiver.peers.getValue("sender").receivedAt)
        val q = DeliveryQueue { 0 }
        q.offer("pli:1", payload, null, 0)
        q.poll(0)
        q.poll(90_000)
        assertTrue(q.acknowledge("pli:1", "receiver"))
    }

    @Test
    fun boundedRetriesExpirationAndNoBurstAfterOutage() {
        val q = DeliveryQueue { 0 }
        q.offer("chat:1", payload, "peer", 0)
        assertEquals(1, q.poll(0)!!.attempt)
        assertEquals(2, q.poll(90_000)!!.attempt)
        assertEquals(3, q.poll(195_000)!!.attempt)
        assertNull(q.poll(299_999))
        assertNull(q.poll(300_000))
        assertEquals(1, q.expired)
        assertEquals(0, q.pending)
        q.offer("chat:2", payload, "peer", 400_000)
        assertNull(q.poll(800_000)) // expired offline, never transmitted on reconnection
    }

    @Test
    fun recipientAndGenerationBoundaries() {
        val q = DeliveryQueue { 0 }
        q.offer("point:1", payload, "peer", 0)
        assertFalse(q.acknowledge("point:1", "peer")) // not submitted
        q.poll(0)
        assertFalse(q.acknowledge("point:1", "other"))
        q.clear()
        assertFalse(q.acknowledge("point:1", "peer"))
        assertNull(q.poll(100_000))
    }

    @Test
    fun latestPliWinsReceiptsHaveSpacingAndQueueIsBounded() {
        val q = DeliveryQueue { 0 }
        repeat(100) { q.offer("pli:$it", payload, null, 0) }
        assertEquals(1, q.pending)
        assertEquals("pli:99", q.poll(0)!!.key)
        repeat(3) { assertTrue(q.offer("chat:$it", payload, "peer", 1)) }
        assertFalse(q.offer("chat:overflow", payload, "peer", 1))
        repeat(32) { assertTrue(q.offer("ack:$it", payload, null, 1, true)) }
        assertFalse(q.offer("ack:overflow", payload, null, 1, true))
        assertNull(q.poll(4999))
        assertTrue(q.poll(5000)!!.receipt)
        assertNull(q.poll(9999))
        assertEquals(35, q.size)
    }

    @Test
    fun compactPositionIsStandaloneAndLegacyStillDecodes() {
        val p = Pli(0xffffffffL, 100_123, 10.1234567, -20.1234567, 60, "HLM1")
        val compact = PliWire.compact(p)
        assertEquals(26, compact.size)
        assertEquals(39, PliWire.encode(p).size)
        val decoded = (PliWire.decode(compact) as PliWire.Position).pli
        assertEquals(p.id, decoded.id)
        assertEquals(100_000L, decoded.fixTime)
        assertEquals(p.lat, decoded.lat, 0.00000011)
        assertEquals(p.callsign, decoded.callsign)
        assertEquals(PliWire.Position(p), PliWire.decode(PliWire.encode(p)))
        assertThrows(IllegalArgumentException::class.java) { PliWire.compact(p.copy(id = -1)) }
        for (n in 0 until 23) assertThrows(Exception::class.java) {
            PliWire.decode(compact.copyOf(n))
        }
    }

    @Test
    fun delayedPositionIsVisibleButStaleAndNewerPositionCannotBeReversed() {
        val s = PliState()
        val p = Pli(1, 100_000, 10.0, 20.0, 60, "A")
        assertTrue(s.accept("peer", p, 0, 400_000))
        assertTrue(s.peers.getValue("peer").stale(0))
        assertFalse(s.accept("peer", p.copy(id = 2, fixTime = 99_000), 1, 400_000))
        assertEquals(PliState.Reception.OLDER, s.lastReception)
        assertFalse(s.accept("other", p, 0, 1_000_001))
        assertEquals(PliState.Reception.EXPIRED, s.lastReception)
    }

    @Test
    fun burstLossAndReceiptStarvationRemainBounded() {
        val q = DeliveryQueue { 15_000 }
        q.offer("pli:1", payload, null, 0)
        assertNotNull(q.poll(0)) // outage
        assertNotNull(q.poll(105_000)) // outage
        assertNotNull(q.poll(225_000)) // link recovered
        assertTrue(q.acknowledge("pli:1", "peer"))
        val lost = DeliveryQueue { 0 }
        lost.offer("pli:2", payload, null, 0)
        repeat(600) { lost.poll(it * 1000L) }
        assertEquals(3, lost.submitted)
        assertEquals(1, lost.expired)
        assertEquals(0, lost.pending)
        val receipts = DeliveryQueue { 0 }
        repeat(32) { receipts.offer("ack:$it", payload, null, 0, true) }
        repeat(60) { receipts.poll(it * 1000L) }
        assertTrue(receipts.submitted <= 6)
        assertEquals(0, receipts.size)
    }

    @Test
    fun seededLossExperimentReportsDeliveryAndCost() {
        // Application-layer independent losses, not an RF/city prediction. Firmware retries are not
        // simulated.
        for (loss in listOf(0.0, 0.3, 0.5, 0.7)) {
            var delivered = 0
            var confirmed = 0
            var transmissions = 0
            var baseline = 0
            val random = Random(20260909)
            repeat(10_000) {
                val q = DeliveryQueue { 0 }
                q.offer("pli:1", payload, null, 0)
                var arrived = false
                for (time in listOf(0L, 90_000L, 195_000L)) {
                    if (q.poll(time) != null) {
                        transmissions++
                        val receive = random.nextDouble() >= loss
                        if (time == 0L && receive) baseline++
                        if (receive) {
                            arrived = true
                            if (random.nextDouble() >= loss) q.acknowledge("pli:1", "peer")
                        }
                    }
                }
                if (arrived) delivered++
                confirmed += q.confirmed
            }
            println(
                "LOSS_EXPERIMENT loss=$loss trials=10000 baselineDelivered=$baseline revisedDelivered=$delivered confirmed=$confirmed dataSubmissions=$transmissions"
            )
            assertTrue(delivered >= baseline)
            assertTrue(transmissions in 10_000..30_000)
            if (loss == 0.0) {
                assertEquals(10_000, delivered)
                assertEquals(10_000, confirmed)
                assertEquals(10_000, transmissions)
            }
        }
    }
}
