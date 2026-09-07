package com.hardlinelabs.relay.atak

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.roundToInt

/** A complete, recipient-addressed point. Revision is sender update time in milliseconds. */
data class MeshPoint(val token: Long, val id: Long, val revision: Long, val recipient: Long,
                     val lat: Double, val lon: Double, val symbol: Int, val color: Int, val name: String)

object PointWire {
    private const val MAGIC = 0x48525031 // HRP1; types 1/2 remain PLI-only.
    const val MAX_NAME_BYTES = 24
    const val MAX_BYTES = 71
    val symbols = listOf("b-m-p-s-m", "a-f-G", "a-h-G", "a-n-G", "a-u-G",
        "a-f-G-U-C", "a-h-G-U-C", "a-n-G-U-C", "a-u-G-U-C")
    sealed interface Message
    data class Point(val point: MeshPoint) : Message
    data class Receipt(val token: Long, val recipient: Long) : Message

    fun isPointMessage(bytes: ByteArray): Boolean = bytes.size >= 5 &&
        ByteBuffer.wrap(bytes).int == MAGIC && bytes[4].toInt() in 3..4

    fun nodeId(node: String): Long {
        require(node.matches(Regex("![0-9a-fA-F]{8}"))) { "Invalid radio identity." }
        return node.substring(1).toLong(16).also { require(it in 1..0xfffffffeL) }
    }

    /** Sender node plus this deterministic ID identifies a point without a full ATAK UUID. */
    fun pointId(uid: String): Long {
        require(uid.isNotBlank())
        return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8)))
            .long.let { if (it == 0L) 1L else it }
    }

    fun encode(p: MeshPoint): ByteArray {
        val name = p.name.toByteArray(Charsets.UTF_8)
        require(p.token != 0L && p.id != 0L && p.revision > 0)
        require(p.recipient in 1..0xfffffffeL)
        require(p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0)
        require(p.symbol in symbols.indices) { "Unsupported point symbol." }
        require(p.name.isNotBlank() && p.name.none { it.isISOControl() } && name.size in 1..MAX_NAME_BYTES) {
            "Point name must be 1–24 UTF-8 bytes with no control characters. Rename it before sending."
        }
        require(decodeName(name) == p.name) { "Invalid point name encoding." }
        return ByteBuffer.allocate(47 + name.size).putInt(MAGIC).put(3)
            .putLong(p.token).putLong(p.id).putLong(p.revision).putInt(p.recipient.toInt())
            .putInt((p.lat * 1e7).roundToInt()).putInt((p.lon * 1e7).roundToInt())
            .put(p.symbol.toByte()).putInt(p.color).put(name.size.toByte()).put(name).array()
    }

    fun receipt(token: Long, recipient: Long): ByteArray {
        require(token != 0L && recipient in 1..0xfffffffeL)
        return ByteBuffer.allocate(17).putInt(MAGIC).put(4).putLong(token).putInt(recipient.toInt()).array()
    }

    fun decode(bytes: ByteArray): Message {
        require(bytes.size in 17..MAX_BYTES)
        val b = ByteBuffer.wrap(bytes)
        require(b.int == MAGIC)
        val type = b.get().toInt()
        val token = b.long
        require(token != 0L)
        if (type == 4) {
            require(bytes.size == 17)
            val recipient = b.int.toLong() and 0xffffffffL
            require(recipient in 1..0xfffffffeL)
            return Receipt(token, recipient)
        }
        require(type == 3 && bytes.size >= 48)
        val id = b.long
        val revision = b.long
        val recipient = b.int.toLong() and 0xffffffffL
        val lat = b.int / 1e7
        val lon = b.int / 1e7
        val symbol = b.get().toInt() and 0xff
        val color = b.int
        val length = b.get().toInt() and 0xff
        require(length in 1..MAX_NAME_BYTES && length == b.remaining())
        val name = ByteArray(length).also { b.get(it) }
        val point = MeshPoint(token, id, revision, recipient, lat, lon, symbol, color, decodeName(name))
        encode(point)
        return Point(point)
    }

    private fun decodeName(bytes: ByteArray) = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}

/** Latest attempt remains visible; unrelated/older receipts cannot overwrite it. */
class PointSendState {
    private data class Attempt(val recipient: Long, val at: Long, var confirmed: Boolean = false)
    private val attempts = linkedMapOf<Long, Attempt>()
    val roundTrips = RoundTrips()
    enum class Status { NONE, SUBMITTING, AWAITING, RECEIVED, UNCONFIRMED, FAILED }
    var status = Status.NONE; private set
    var point: MeshPoint? = null; private set
    var recipientName = ""; private set
    var detail = "No point sent this session."; private set
    private var startedAt = 0L
    private var receiptEligible = false

    fun clear() {
        attempts.clear(); roundTrips.clear()
        status = Status.NONE; point = null; recipientName = ""
        detail = "No point sent this session."; receiptEligible = false
    }

    fun begin(point: MeshPoint, recipientName: String, now: Long) {
        attempts.entries.removeAll { now - it.value.at > 300_000 }
        attempts[point.token] = Attempt(point.recipient, now)
        while (attempts.size > 32) attempts.remove(attempts.keys.first())
        this.point = point; this.recipientName = recipientName; startedAt = now
        receiptEligible = true
        status = Status.SUBMITTING; detail = "Submitting to radio…"
    }
    fun submitted(token: Long) {
        if (point?.token == token && status == Status.SUBMITTING) {
            status = Status.AWAITING; detail = "Awaiting recipient plugin receipt."
        }
    }
    fun failed(token: Long?, reason: String) {
        if (token != null && point?.token != token) return
        if (token != null && status == Status.RECEIVED) return
        if (token == null) { point = null; recipientName = "" }
        status = Status.FAILED; detail = reason
        receiptEligible = false
    }
    fun receipt(token: Long, from: Long, now: Long): Boolean {
        attempts[token]?.takeIf { !it.confirmed && it.recipient == from && now - it.at in 0..300_000 }?.let {
            it.confirmed = true; roundTrips.add(now - it.at)
        }
        val p = point ?: return false
        if (!receiptEligible || p.token != token || p.recipient != from || now - startedAt !in 0..300_000 ||
            status !in listOf(Status.SUBMITTING, Status.AWAITING, Status.UNCONFIRMED)) return false
        status = Status.RECEIVED; detail = "Recipient plugin created/updated the point."
        return true
    }
    fun tick(now: Long) {
        if (status in listOf(Status.SUBMITTING, Status.AWAITING) && now - startedAt >= 60_000) {
            status = Status.UNCONFIRMED; detail = "No receipt after 60s; delivery is unknown."
        }
    }
    fun interrupted() {
        attempts.clear(); roundTrips.clear()
        receiptEligible = false
        if (status in listOf(Status.SUBMITTING, Status.AWAITING)) {
            status = Status.UNCONFIRMED; detail = "Connection/channel changed; delivery is unknown."
        }
    }
}

/** Decisions are committed only after the ATAK marker update succeeds. */
class PointReceiveState {
    enum class Decision { IMPORT, RECEIPT_ONLY, IGNORE }
    private data class Key(val sender: Long, val id: Long)
    private data class Received(val point: MeshPoint, var acknowledgedAt: Long)
    private val received = linkedMapOf<Key, Received>()

    fun decide(sender: Long, local: Long, p: MeshPoint, now: Long): Decision {
        if (p.recipient != local || sender == local) return Decision.IGNORE
        val old = received[Key(sender, p.id)]
        if (old == null) return if (received.size < 128) Decision.IMPORT else Decision.IGNORE
        if (p.revision < old.point.revision) return Decision.IGNORE
        if (p.revision > old.point.revision) return Decision.IMPORT
        // Same revision must describe identical data; a retry can carry a new attempt token.
        if (p.copy(token = old.point.token) != old.point) return Decision.IGNORE
        return if (now - old.acknowledgedAt >= 5_000) Decision.RECEIPT_ONLY else Decision.IGNORE
    }
    fun committed(sender: Long, p: MeshPoint, now: Long) { received[Key(sender, p.id)] = Received(p, now) }
    fun clear() = received.clear()
}
