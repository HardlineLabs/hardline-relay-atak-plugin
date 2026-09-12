package com.hardlinelabs.relay.atak

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ChatTest {
    private val text =
        ChatWire.Text(
            UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
            42,
            1000,
            "Team",
            "Hello teammate\n✓",
        )

    @Test
    fun unicodeTextAndReceiptRoundTrip() {
        assertEquals(text, ChatWire.decode(ChatWire.encode(text)))
        val receipt = ChatWire.Receipt(text.id, 43)
        assertEquals(25, ChatWire.encode(receipt).size)
        assertEquals(receipt, ChatWire.decode(ChatWire.encode(receipt)))
        assertTrue(ChatWire.isChat(ChatWire.encode(text)))
        assertFalse(ChatWire.isChat(PliWire.ack(42)))
    }

    @Test
    fun byteLimitRejectsWithoutTruncating() {
        assertEquals(
            218,
            ChatWire.encode(text.copy(callsign = "A".repeat(24), text = "A".repeat(160))).size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChatWire.encode(text.copy(text = "é".repeat(81)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatWire.encode(text.copy(text = " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatWire.encode(text.copy(recipient = 0xffffffffL))
        }
    }

    @Test
    fun malformedPacketsAndUnaddressedRoomsAreRejected() {
        val bytes = ChatWire.encode(text)
        for (n in 0..35) assertThrows(Exception::class.java) {
            ChatWire.decode(bytes.take(n).toByteArray())
        }
        assertThrows(Exception::class.java) {
            ChatWire.decode(bytes.copyOf().apply { this[34] = 0xff.toByte() })
        }
        assertThrows(Exception::class.java) {
            ChatWire.decode(ChatWire.encode(ChatWire.Receipt(text.id, 42)) + 0)
        }
        assertThrows(Exception::class.java) { ChatWire.encode(text.copy(recipient = 0)) }
    }

    @Test
    fun roundTripSamplesStayBounded() {
        val stats = RoundTrips()
        (1..100).forEach { stats.add(it.toLong()) }
        assertEquals(32, stats.count)
        assertEquals(100L, stats.latest)
        assertEquals(100L, stats.longest)
        assertEquals(84L, stats.average)
    }
}
