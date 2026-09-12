package com.hardlinelabs.relay.atak

/** One bounded outbox for PLI, chat, points and their receipts. Clock and jitter are injected. */
class DeliveryQueue(private val jitter: () -> Long) {
    data class Frame(val key: String, val bytes: ByteArray, val receipt: Boolean, val attempt: Int)

    private data class Job(
        val key: String,
        val bytes: ByteArray,
        val recipient: String?,
        val receipt: Boolean,
        val created: Long,
        var due: Long,
        var attempts: Int = 0,
    )

    private val jobs = linkedMapOf<String, Job>()
    private var nextFrame = 0L
    private var nextData = 0L
    var submitted = 0
        private set

    var retries = 0
        private set

    var confirmed = 0
        private set

    var expired = 0
        private set

    var bytesSubmitted = 0L
        private set

    val pending
        get() = jobs.values.count { !it.receipt }

    val size
        get() = jobs.size

    fun fits(maxBytes: Int) = jobs.values.all { it.bytes.size <= maxBytes }

    fun offer(
        key: String,
        bytes: ByteArray,
        recipient: String?,
        now: Long,
        receipt: Boolean = false,
    ): Boolean {
        require(bytes.size in 1..218)
        if (key in jobs) return true
        if (receipt) {
            if (jobs.values.count { it.receipt } >= 32) return false
        } else {
            if (key.startsWith("pli:"))
                jobs.keys.filter { it.startsWith("pli:") }.forEach(jobs::remove)
            if (pending >= 4) return false
        }
        jobs[key] =
            Job(
                key,
                bytes.copyOf(),
                recipient,
                receipt,
                now,
                now + if (receipt) 500 + jitter().coerceIn(0, 2500) else 0,
            )
        return true
    }

    fun acknowledge(key: String, from: String): Boolean {
        val job = jobs[key] ?: return false
        if (job.receipt || job.attempts == 0 || (job.recipient != null && job.recipient != from))
            return false
        jobs.remove(key)
        confirmed++
        return true
    }

    fun poll(now: Long): Frame? {
        val old = jobs.values.filter { now - it.created >= if (it.receipt) 30_000 else 300_000 }
        old.forEach {
            if (!it.receipt) expired++
            jobs.remove(it.key)
        }
        if (now < nextFrame) return null
        val job =
            jobs.values
                .filter { now >= it.due && (it.receipt || now >= nextData) && it.attempts < 3 }
                .sortedByDescending { it.receipt }
                .firstOrNull() ?: return null
        job.attempts++
        submitted++
        bytesSubmitted += job.bytes.size
        if (job.attempts > 1) retries++
        nextFrame = now + 5000
        if (job.receipt) jobs.remove(job.key)
        else {
            nextData = now + 15_000
            // Let firmware complete its own bounded reliable attempt before another submission.
            job.due =
                now + (if (job.attempts == 1) 90_000 else 105_000) + jitter().coerceIn(0, 15_000)
        }
        return Frame(job.key, job.bytes.copyOf(), job.receipt, job.attempts)
    }

    fun cancelPli() {
        jobs.keys.filter { it.startsWith("pli:") }.forEach(jobs::remove)
    }

    fun clear() {
        jobs.clear()
        nextFrame = 0
        nextData = 0
        submitted = 0
        retries = 0
        confirmed = 0
        expired = 0
        bytesSubmitted = 0
    }
}
