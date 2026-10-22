package com.egouvc.phone.usb

object UsbDeviceMatcher {
    const val VENDOR_ID = 0x1bcf
    const val PRODUCT_ID = 0x0b15

    fun matches(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID && productId == PRODUCT_ID
}
