package com.egouvc.phone.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMeterTest {
    @Test fun reportsDeliveredFramesPerBootTimeWindow() {
        val meter = FrameRateMeter()
        assertNull(meter.onFrame(1_000_000_000L))
        assertNull(meter.onFrame(1_500_000_000L))
        assertEquals(3.0, meter.onFrame(2_000_000_000L)!!, 0.0001)
        assertEquals(3L, meter.totalFrames)
        assertNull(meter.onFrame(2_500_000_000L))
        assertEquals(2.0, meter.onFrame(3_000_000_000L)!!, 0.0001)
    }
}
