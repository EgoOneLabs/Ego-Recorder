package com.egouvc.phone

import android.app.Application
import com.egouvc.phone.usb.UsbDeviceManager

class EgoUvcApplication : Application() {
    val usbDeviceManager: UsbDeviceManager by lazy { UsbDeviceManager(this) }

    override fun onCreate() {
        super.onCreate()
        usbDeviceManager.start()
    }
}
