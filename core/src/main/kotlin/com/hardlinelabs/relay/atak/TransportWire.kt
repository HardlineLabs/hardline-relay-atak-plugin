package com.hardlinelabs.relay.atak

import java.util.Base64

/** Explicit private-channel text fallback. No fragmentation or interpretation of ordinary chats. */
object TransportWire {
    const val PREFIX = "HLR1:"
    const val MAX_BINARY = 165
    const val MAX_TEXT = 225

    fun text(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..MAX_BINARY) {
            "Text transport packet too long. Shorten the chat message; positions and points already fit."
        }
        return (PREFIX + Base64.getEncoder().encodeToString(bytes)).toByteArray(Charsets.US_ASCII)
    }

    fun decodeText(bytes: ByteArray): ByteArray? {
        if (
            bytes.size < PREFIX.length ||
                !bytes
                    .copyOfRange(0, PREFIX.length)
                    .contentEquals(PREFIX.toByteArray(Charsets.US_ASCII))
        )
            return null
        require(bytes.size <= MAX_TEXT) { "Oversized Hardline text" }
        val decoded = Base64.getDecoder().decode(bytes.copyOfRange(PREFIX.length, bytes.size))
        require(decoded.size in 1..MAX_BINARY)
        require(
            decoded.size >= 5 &&
                decoded.copyOfRange(0, 4).contentEquals(byteArrayOf(0x48, 0x52, 0x50, 0x31))
        )
        return decoded
    }
}
