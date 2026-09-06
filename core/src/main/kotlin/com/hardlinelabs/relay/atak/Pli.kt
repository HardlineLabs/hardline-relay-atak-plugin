package com.hardlinelabs.relay.atak

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private experimental wire format. No keys, raw CoT XML, or server forwarding. */
data class Pli(val id: Long, val fixTime: Long, val lat: Double, val lon: Double,
               val interval: Int, val callsign: String)

object PliWire {
    const val PORT = 256
    private const val MAGIC = 0x48525031 // HRP1
    fun encode(p: Pli): ByteArray {
        require(p.id != 0L && p.fixTime > 0 && p.lat.isFinite() && p.lon.isFinite())
        require(p.lat in -90.0..90.0 && p.lon in -180.0..180.0)
        require(p.interval in listOf(0, 10, 30))
        val name = p.callsign.toByteArray(Charsets.UTF_8)
        require(name.size in 1..40 && p.callsign.none { it.isISOControl() })
        return ByteBuffer.allocate(35 + name.size).putInt(MAGIC).put(1).putLong(p.id)
            .putLong(p.fixTime).putInt((p.lat * 1e7).toInt()).putInt((p.lon * 1e7).toInt())
            .putInt(p.interval).putShort(name.size.toShort()).put(name).array()
    }
    fun ack(id: Long): ByteArray {
        require(id != 0L)
        return ByteBuffer.allocate(13).putInt(MAGIC).put(2).putLong(id).array()
    }
    sealed interface Message
    data class Position(val pli: Pli) : Message
    data class Receipt(val id: Long) : Message
    fun decode(bytes: ByteArray): Message {
        require(bytes.size in 13..75)
        val b = ByteBuffer.wrap(bytes)
        require(b.int == MAGIC)
        val type = b.get().toInt()
        val id = b.long
        require(id != 0L)
        if (type == 2) { require(!b.hasRemaining()); return Receipt(id) }
        require(type == 1 && bytes.size >= 36)
        val time = b.long
        val lat = b.int / 1e7
        val lon = b.int / 1e7
        val interval = b.int
        val length = b.short.toInt()
        require(length in 1..40 && length == b.remaining())
        val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(b).toString()
        val p = Pli(id, time, lat, lon, interval, name)
        encode(p) // Validate values as strictly as the encoder.
        return Position(p)
    }
}

/** Monotonic receipt ages: remote clocks never decide whether a peer is active. */
class PliState {
    data class Peer(val pli: Pli, val receivedAt: Long, val receivedWall: Long) {
        fun age(now: Long) = ((now - receivedAt).coerceAtLeast(0) / 1000)
        fun stale(now: Long) = now - receivedAt >= maxOf(60_000L, pli.interval * 3_000L)
    }
    data class Sent(val id: Long, val at: Long, val receipts: MutableSet<String> = linkedSetOf())
    val peers = linkedMapOf<String, Peer>()
    val pending = linkedMapOf<Long, Sent>()
    private val seen = linkedMapOf<String, Long>()
    fun sent(id: Long, now: Long) {
        expire(now)
        pending[id] = Sent(id, now)
        while (pending.size > 32) pending.remove(pending.keys.first())
    }
    fun receipt(id: Long, from: String, now: Long): Boolean {
        expire(now)
        val sent = pending[id] ?: return false
        if (sent.receipts.size >= 32) return false
        return sent.receipts.add(from)
    }
    fun accept(from: String, p: Pli, now: Long, wall: Long): Boolean {
        expire(now)
        val key = "$from:${p.id}"
        if (seen.containsKey(key)) return false
        seen[key] = now
        while (seen.size > 256) seen.remove(seen.keys.first())
        val previous = peers[from]
        // A delayed older fix must not move a marker backwards or refresh its age.
        if (previous != null && p.fixTime < previous.pli.fixTime) return false
        if (p.fixTime > wall + 30_000 || wall - p.fixTime > 120_000) return false
        if (!peers.containsKey(from) && peers.size >= 32) peers.remove(peers.keys.first())
        peers[from] = Peer(p, now, wall)
        return true
    }
    fun expire(now: Long) {
        seen.entries.removeAll { now - it.value > 300_000 }
        pending.entries.removeAll { now - it.value.at > 300_000 }
    }
    fun clear() { peers.clear(); pending.clear(); seen.clear() }
}
