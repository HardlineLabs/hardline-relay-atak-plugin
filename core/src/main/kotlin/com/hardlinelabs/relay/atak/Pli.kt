package com.hardlinelabs.relay.atak

import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private experimental wire format. No keys, raw CoT XML, or server forwarding. */
data class Pli(val id: Long, val fixTime: Long, val lat: Double, val lon: Double,
               val interval: Int, val callsign: String)

object PliWire {
    val intervals = listOf(0, 10, 30, 60, 120, 300, 600)
    const val PORT = 256
    private const val MAGIC = 0x48525031 // HRP1
    fun encode(p: Pli): ByteArray {
        require(p.id != 0L && p.fixTime > 0 && p.lat.isFinite() && p.lon.isFinite())
        require(p.lat in -90.0..90.0 && p.lon in -180.0..180.0)
        require(p.interval in intervals)
        val name = p.callsign.toByteArray(Charsets.UTF_8)
        require(name.size in 1..40 && p.callsign.none { it.isISOControl() })
        return ByteBuffer.allocate(35 + name.size).putInt(MAGIC).put(1).putLong(p.id)
            .putLong(p.fixTime).putInt((p.lat * 1e7).roundToInt()).putInt((p.lon * 1e7).roundToInt())
            .putInt(p.interval).putShort(name.size.toShort()).put(name).array()
    }
    /** V2: absolute position, uint32 token/seconds, interval index, bounded UTF-8 name. */
    fun compact(p: Pli): ByteArray {
        encode(p) // Same coordinate/name validation as v1.
        require(p.id in 1..0xffffffffL && p.fixTime / 1000 in 1..0xffffffffL)
        val name = p.callsign.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(22 + name.size).putInt(MAGIC).put(7).putInt(p.id.toInt())
            .putInt((p.fixTime / 1000).toInt()).putInt((p.lat * 1e7).roundToInt()).putInt((p.lon * 1e7).roundToInt())
            .put(intervals.indexOf(p.interval).toByte()).put(name).array()
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
        if (type == 7) {
            require(bytes.size in 23..62)
            val id = b.int.toLong() and 0xffffffffL
            val time = (b.int.toLong() and 0xffffffffL) * 1000
            val lat = b.int / 1e7; val lon = b.int / 1e7
            val interval = intervals.getOrNull(b.get().toInt()) ?: error("Invalid interval")
            val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(b).toString()
            return Position(Pli(id, time, lat, lon, interval, name).also { compact(it) })
        }
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
        fun stale(now: Long) = now - receivedAt + (receivedWall - pli.fixTime).coerceAtLeast(0) >= maxOf(60_000L, pli.interval * 3_000L)
    }
    data class Sent(val id: Long, val at: Long, val receipts: MutableSet<String> = linkedSetOf(),
                    var failure: String? = null, var deadlineReached: Boolean = false)
    val roundTrips = RoundTrips()
    val unanswered get() = pending.values.count { it.deadlineReached && it.receipts.isEmpty() }
    val peers = linkedMapOf<String, Peer>()
    val pending = linkedMapOf<Long, Sent>()
    var latest: Sent? = null
        private set
    var lastConfirmation = -1L
        private set
    private val seen = linkedMapOf<String, Long>()
    /** Only the latest attempt can hold the timer; a late receipt belongs to its original attempt. */
    fun waiting(now: Long, waitMillis: Long): Sent? {
        val attempt = latest ?: return null
        if (attempt.receipts.isNotEmpty() || attempt.failure != null || attempt.deadlineReached) return null
        if (now - attempt.at >= waitMillis) { attempt.deadlineReached = true; return null }
        return attempt
    }
    fun sent(id: Long, now: Long) {
        expire(now)
        pending[id] = Sent(id, now)
        latest = pending[id]
        while (pending.size > 32) pending.remove(pending.keys.first())
    }
    fun receipt(id: Long, from: String, now: Long): Boolean {
        expire(now)
        val sent = pending[id] ?: return false
        if (sent.receipts.size >= 32) return false
        if (now < sent.at) return false
        val first = sent.receipts.isEmpty()
        return sent.receipts.add(from).also { if (it) {
            if (first) roundTrips.add(now - sent.at)
            lastConfirmation = now; sent.failure = null
        } }
    }
    fun failed(id: Long, reason: String) { pending[id]?.takeIf { it.receipts.isEmpty() }?.failure = reason }
    enum class Reception { ACCEPTED, DUPLICATE, OLDER, EXPIRED, FUTURE }
    var lastReception = Reception.ACCEPTED; private set
    fun accept(from: String, p: Pli, now: Long, wall: Long): Boolean {
        expire(now)
        val key = "$from:${p.id}"
        lastReception = Reception.ACCEPTED
        if (seen.containsKey(key)) { lastReception = Reception.DUPLICATE; return false }
        val previous = peers[from]
        // A delayed older fix must not move a marker backwards or refresh its age.
        if (p.fixTime > wall + 30_000) { lastReception = Reception.FUTURE; return false }
        if (wall - p.fixTime > 900_000) { lastReception = Reception.EXPIRED; return false }
        if (previous != null && p.fixTime < previous.pli.fixTime) { lastReception = Reception.OLDER; return false }
        seen[key] = now
        while (seen.size > 256) seen.remove(seen.keys.first())
        if (!peers.containsKey(from) && peers.size >= 32) peers.remove(peers.keys.first())
        peers[from] = Peer(p, now, wall)
        return true
    }
    fun expire(now: Long) {
        seen.entries.removeAll { now - it.value > 300_000 }
        pending.entries.removeAll { now - it.value.at > 300_000 }
    }
    fun clear() { peers.clear(); pending.clear(); seen.clear(); latest = null; lastConfirmation = -1; roundTrips.clear() }
}
