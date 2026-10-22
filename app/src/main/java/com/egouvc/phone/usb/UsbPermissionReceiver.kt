package com.egouvc.phone.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.egouvc.phone.EgoUvcApplication

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbDeviceManager.ACTION_USB_PERMISSION) return
        val app = context.applicationContext as? EgoUvcApplication
        if (app == null) {
            Log.e("EgoUVCPhone", "USB permission callback has no app context")
            return
        }
        app.usbDeviceManager.onPermissionResult(intent)
    }
}
