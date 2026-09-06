package com.hardlinelabs.relay.atak

/** Main-thread session state, with a generation visible to the radio worker. */
class RadioSession<T> {
    var snapshot: T? = null; private set
    var selected: Int? = null; private set
    @Volatile var generation = 0; private set
    var busy = false; private set
    var queued = 0; private set

    private fun invalidateWork() { generation++; busy = false; queued = 0 }
    fun reset() { invalidateWork(); snapshot = null; selected = null }
    fun select(index: Int?) {
        require(index == null || (snapshot != null && index > 0))
        invalidateWork(); selected = index
    }
    /** True means the host must rebuild choices, including a connected-to-connected change. */
    fun observe(current: T): Boolean {
        val changed = snapshot != current
        if (changed && snapshot != null) reset()
        snapshot = current
        return changed
    }
    fun beginRefresh(): Int? {
        if (busy) return null
        busy = true; return generation
    }
    fun finishRefresh(epoch: Int): Boolean {
        if (epoch != generation) return false
        busy = false; return true
    }
    fun beginTransmit(): Int? {
        if (queued >= 4) return null
        queued++; return generation
    }
    fun finishTransmit(epoch: Int): Boolean {
        if (epoch != generation) return false
        queued = (queued - 1).coerceAtLeast(0); return true
    }
}
