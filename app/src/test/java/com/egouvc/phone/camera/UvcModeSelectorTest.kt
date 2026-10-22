package com.egouvc.phone.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UvcModeSelectorTest {
    @Test fun rejectsResolutionOnlyMatchAndUncompressedMode() {
        val modes = listOf(
            UvcMode(5, 3840, 1080, listOf(60)),
            UvcMode(7, 3840, 1080, listOf(30)),
            UvcMode(7, 1920, 1080, listOf(60)),
        )
        assertNull(UvcModeSelector.target(modes))
    }

    @Test fun selectsExactStereoMjpegSixtyMode() {
        val expected = UvcMode(7, 3840, 1080, listOf(30, 60))
        assertEquals(expected, UvcModeSelector.target(listOf(UvcMode(5, 3840, 1080, listOf(60)), expected)))
    }
}
