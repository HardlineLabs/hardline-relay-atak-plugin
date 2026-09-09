package com.hardlinelabs.relay.atak
import org.junit.Assert.*
import org.junit.Test
class TransportWireTest {
    @Test fun textRoundTripsAllPacketFamiliesAndOrdinaryChatIsIgnored() {
        val p = Pli(1, 100_000, 10.0, 20.0, 60, "HLM1")
        val point = MeshPoint(1, 2, 100_000, 3, 10.0, 20.0, 0, 0, "Point")
        val chat = ChatWire.Text(java.util.UUID.randomUUID(), 3, 100_000, "A", "Hello")
        for (bytes in listOf(PliWire.compact(p), PliWire.encode(p), PliWire.ack(1), PointWire.encode(point), PointWire.receipt(1, 3), ChatWire.encode(chat), ChatWire.encode(ChatWire.Receipt(chat.id, 3)))) {
            assertArrayEquals(bytes, TransportWire.decodeText(TransportWire.text(bytes)))
        }
        assertEquals(41, TransportWire.text(PliWire.compact(p)).size)
        assertNull(TransportWire.decodeText("Hello teammate".toByteArray()))
    }
    @Test fun boundsInvalidEncodingAndNoFragmentation() {
        assertEquals(225, TransportWire.text(ByteArray(165)).size)
        assertThrows(IllegalArgumentException::class.java) { TransportWire.text(ByteArray(166)) }
        assertThrows(IllegalArgumentException::class.java) { TransportWire.decodeText("HLR1:???".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { TransportWire.decodeText("HLR1:".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { TransportWire.decodeText(ByteArray(226).apply { "HLR1:".toByteArray().copyInto(this) }) }
        val q = DeliveryQueue { 0 }; q.offer("chat:1", ByteArray(218), "peer", 0)
        assertFalse(q.fits(165)); q.clear(); assertTrue(q.fits(165))
    }
}
