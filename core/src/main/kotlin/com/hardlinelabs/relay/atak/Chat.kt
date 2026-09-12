package com.hardlinelabs.relay.atak

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * Single-packet private-channel chat. Destination is an application recipient, not a public room.
 */
object ChatWire {
    private const val MAGIC = 0x48525031
    const val MAX_TEXT_BYTES = 160

    sealed interface Message {
        val id: UUID
        val recipient: Long
    }

    data class Text(
        override val id: UUID,
        override val recipient: Long,
        val time: Long,
        val callsign: String,
        val text: String,
    ) : Message

    data class Receipt(override val id: UUID, override val recipient: Long) : Message

    fun isChat(bytes: ByteArray) =
        bytes.size >= 5 && ByteBuffer.wrap(bytes).int == MAGIC && bytes[4].toInt() in 5..6

    fun encode(message: Message): ByteArray {
        require(message.recipient in 1..0xfffffffeL)
        val name =
            if (message is Text) message.callsign.toByteArray(Charsets.UTF_8) else byteArrayOf()
        val body = if (message is Text) message.text.toByteArray(Charsets.UTF_8) else byteArrayOf()
        if (message is Text) {
            require(
                message.time > 0 &&
                    name.size in 1..24 &&
                    message.callsign.none { it.isISOControl() }
            )
            require(
                body.size in 1..MAX_TEXT_BYTES &&
                    message.text.isNotBlank() &&
                    message.text.none { it.isISOControl() && it != '\n' && it != '\t' }
            ) {
                "Relay chat supports 1–160 UTF-8 bytes per message. Shorten the message and send again."
            }
            require(utf8(name) == message.callsign && utf8(body) == message.text)
        }
        val b = ByteBuffer.allocate(if (message is Text) 34 + name.size + body.size else 25)
        b.putInt(MAGIC)
            .put(if (message is Text) 5.toByte() else 6.toByte())
            .putLong(message.id.mostSignificantBits)
            .putLong(message.id.leastSignificantBits)
            .putInt(message.recipient.toInt())
        if (message is Text) b.putLong(message.time).put(name.size.toByte()).put(name).put(body)
        return b.array()
    }

    fun decode(bytes: ByteArray): Message {
        require(bytes.size in 25..218)
        val b = ByteBuffer.wrap(bytes)
        require(b.int == MAGIC)
        val type = b.get().toInt()
        val id = UUID(b.long, b.long)
        val recipient = b.int.toLong() and 0xffffffffL
        val result =
            if (type == 6) {
                require(!b.hasRemaining())
                Receipt(id, recipient)
            } else {
                require(type == 5 && bytes.size >= 36)
                val time = b.long
                val length = b.get().toInt() and 0xff
                require(length in 1..24 && b.remaining() > length)
                val name = ByteArray(length).also(b::get)
                val body = ByteArray(b.remaining()).also(b::get)
                Text(id, recipient, time, utf8(name), utf8(body))
            }
        encode(result)
        return result
    }

    private fun utf8(bytes: ByteArray) =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
}
