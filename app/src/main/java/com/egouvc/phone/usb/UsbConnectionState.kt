package com.egouvc.phone.usb

enum class UsbConnectionState {
    DISCONNECTED,
    CAMERA_PERMISSION_REQUIRED,
    USB_PERMISSION_REQUIRED,
    USB_PERMISSION_PENDING,
    READY,
    ERROR,
}

data class UsbStatus(
    val state: UsbConnectionState,
    val hostSupported: Boolean,
    val deviceName: String? = null,
    val productName: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val cameraPermissionGranted: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val message: String? = null,
)

object UsbStateResolver {
    fun resolve(
        hostSupported: Boolean,
        devicePresent: Boolean,
        cameraPermissionGranted: Boolean,
        usbPermissionGranted: Boolean,
        permissionPending: Boolean,
    ): UsbConnectionState = when {
        !hostSupported -> UsbConnectionState.ERROR
        !devicePresent -> UsbConnectionState.DISCONNECTED
        !cameraPermissionGranted -> UsbConnectionState.CAMERA_PERMISSION_REQUIRED
        usbPermissionGranted -> UsbConnectionState.READY
        permissionPending -> UsbConnectionState.USB_PERMISSION_PENDING
        else -> UsbConnectionState.USB_PERMISSION_REQUIRED
    }
}
