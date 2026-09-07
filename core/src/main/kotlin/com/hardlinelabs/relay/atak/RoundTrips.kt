package com.hardlinelabs.relay.atak

/** Recent matched application receipts, measured with the local monotonic clock. */
class RoundTrips {
    private val samples = ArrayDeque<Long>()
    val count get() = samples.size
    val average get() = if (samples.isEmpty()) null else samples.average().toLong()
    val latest get() = samples.lastOrNull()
    val longest get() = samples.maxOrNull()
    fun add(milliseconds: Long) {
        require(milliseconds >= 0)
        samples.addLast(milliseconds)
        if (samples.size > 32) samples.removeFirst()
    }
    fun clear() = samples.clear()
}
