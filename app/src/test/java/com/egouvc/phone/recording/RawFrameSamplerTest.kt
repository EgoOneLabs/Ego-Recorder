package com.egouvc.phone.recording

import org.junit.Assert.assertTrue
import org.junit.Test

class RawFrameSamplerTest {
    @Test fun selectsAboutThirtyFromFortyFiveFps() {
        val sampler = RawFrameSampler(30)
        val selected = (0 until 450).count { sampler.select(1_000_000_000L + it * 1_000_000_000L / 45L) }
        assertTrue("selected $selected", selected in 298..302)
    }

    @Test fun selectsAboutThirtyFromSixtyFps() {
        val sampler = RawFrameSampler(30)
        val selected = (0 until 600).count { sampler.select(1_000_000_000L + it * 1_000_000_000L / 60L) }
        assertTrue("selected $selected", selected in 298..302)
    }

    @Test fun doesNotBurstAfterLongStall() {
        val sampler = RawFrameSampler(30)
        assertTrue(sampler.select(1_000_000_000L))
        assertTrue(sampler.select(2_000_000_000L))
        assertTrue(!sampler.select(2_001_000_000L))
    }
}
