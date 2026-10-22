package com.egouvc.phone.camera

/** Counts delivered callbacks in CLOCK_BOOTTIME windows. No JPEG byte count is inferred. */
class FrameRateMeter {
    private var windowStartNs = 0L
    private var frames = 0
    var totalFrames: Long = 0
        private set

    @Synchronized
    fun onFrame(bootTimeNs: Long): Double? {
        if (windowStartNs == 0L) windowStartNs = bootTimeNs
        frames++
        totalFrames++
        val elapsed = bootTimeNs - windowStartNs
        if (elapsed < 1_000_000_000L) return null
        val fps = frames * 1_000_000_000.0 / elapsed
        windowStartNs = bootTimeNs
        frames = 0
        return fps
    }
}
