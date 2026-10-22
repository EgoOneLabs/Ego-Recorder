package com.egouvc.phone.recording

/** Selects at most the target time cadence without resetting phase after every chosen frame. */
class RawFrameSampler(private val targetFps: Int = 30) {
    init { require(targetFps in 1..120) }
    private val periodNs = 1_000_000_000L / targetFps
    private var nextDueNs = 0L

    @Synchronized
    fun select(receiveBootNs: Long): Boolean {
        if (receiveBootNs < nextDueNs) return false
        nextDueNs = if (nextDueNs == 0L || receiveBootNs - nextDueNs >= 2L * periodNs) {
            receiveBootNs + periodNs
        } else {
            nextDueNs + periodNs
        }
        return true
    }
}
