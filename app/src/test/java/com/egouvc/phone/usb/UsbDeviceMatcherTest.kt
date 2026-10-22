package com.egouvc.phone.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceMatcherTest {
    @Test fun acceptsOnlyTargetVidAndPid() {
        assertTrue(UsbDeviceMatcher.matches(0x1bcf, 0x0b15))
        assertFalse(UsbDeviceMatcher.matches(0x1bce, 0x0b15))
        assertFalse(UsbDeviceMatcher.matches(0x1bcf, 0x0b16))
        assertFalse(UsbDeviceMatcher.matches(0, 0))
    }
}
