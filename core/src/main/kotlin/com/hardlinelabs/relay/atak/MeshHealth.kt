package com.hardlinelabs.relay.atak

object MeshHealth {
    enum class State { INACTIVE, FAULT, WAITING, CONFIRMED }
    fun assess(connected: Boolean, selected: Boolean, recentPeer: Boolean, positionBlocked: Boolean): State = when {
        !connected -> State.FAULT
        !selected -> State.INACTIVE
        positionBlocked || !recentPeer -> State.WAITING
        else -> State.CONFIRMED
    }
}

/** Coalesce automatic receipts per sender; bound total airtime and avoid synchronized replies. */
class ReceiptSchedule {
    private data class Waiting(val id: Long, val due: Long)
    private val pending = linkedMapOf<String, Waiting>()
    private val last = linkedMapOf<String, Long>()
    private var nextGlobal = 0L
    fun enqueue(from: String, pli: Pli, now: Long, jitter: Long) {
        if (pending.size >= 32 && from !in pending) return
        val due = maxOf(now + jitter.coerceIn(500, 3000),
            if (pli.interval == 0) now else (last[from] ?: -60_000L) + 60_000L)
        pending[from] = Waiting(pli.id, pending[from]?.due?.coerceAtMost(due) ?: due)
    }
    fun poll(now: Long): Long? {
        if (now < nextGlobal) return null
        val entry = pending.entries.firstOrNull { now >= it.value.due } ?: return null
        pending.remove(entry.key)
        last[entry.key] = now
        while (last.size > 32) last.remove(last.keys.first())
        nextGlobal = now + 5000
        return entry.value.id
    }
    fun clear() { pending.clear(); last.clear(); nextGlobal = 0 }
}
