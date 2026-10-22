package com.egouvc.phone.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbStateResolverTest {
    @Test fun resolvesEachPermissionAndConnectionStage() {
        val resolve = UsbStateResolver::resolve
        assertEquals(UsbConnectionState.ERROR, resolve(false, false, false, false, false))
        assertEquals(UsbConnectionState.DISCONNECTED, resolve(true, false, false, false, false))
        assertEquals(UsbConnectionState.CAMERA_PERMISSION_REQUIRED, resolve(true, true, false, false, false))
        assertEquals(UsbConnectionState.USB_PERMISSION_REQUIRED, resolve(true, true, true, false, false))
        assertEquals(UsbConnectionState.USB_PERMISSION_PENDING, resolve(true, true, true, false, true))
        assertEquals(UsbConnectionState.READY, resolve(true, true, true, true, false))
    }

    @Test fun neverReportsReadyWithoutAConnectedDevice() {
        val state = UsbStateResolver.resolve(
            hostSupported = true,
            devicePresent = false,
            cameraPermissionGranted = true,
            usbPermissionGranted = true,
            permissionPending = true,
        )
        assertEquals(UsbConnectionState.DISCONNECTED, state)
    }
}
