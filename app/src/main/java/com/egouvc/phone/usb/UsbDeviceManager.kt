package com.egouvc.phone.usb

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.UUID

class UsbDeviceManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private var currentDevice: UsbDevice? = null
    private var pendingRequest: PendingRequest? = null
    private var observer: ((UsbStatus) -> Unit)? = null
    private var feedback: String? = null
    private var started = false

    var status = UsbStatus(UsbConnectionState.DISCONNECTED, hostSupported = false)
        private set

    private val usbEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiveContext: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> Log.i(TAG, "USB attached; rescanning")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> Log.i(TAG, "USB detached; rescanning")
                else -> return
            }
            refresh()
        }
    }

    fun start() {
        if (started) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbEventsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbEventsReceiver, filter)
        }
        started = true
        refresh()
    }

    fun observe(callback: (UsbStatus) -> Unit) {
        observer = callback
        callback(status)
    }

    fun removeObserver(callback: (UsbStatus) -> Unit) {
        if (observer === callback) observer = null
    }

    fun refresh() {
        val hostSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hostSupported || usbManager == null) {
            currentDevice = null
            pendingRequest = null
            feedback = null
            publish(
                UsbStatus(
                    state = UsbConnectionState.ERROR,
                    hostSupported = false,
                    cameraPermissionGranted = cameraGranted,
                    message = "此手机未提供 USB Host/OTG 功能。",
                ),
            )
            Log.e(TAG, "Scan failed: USB Host unavailable")
            return
        }

        try {
            val devices = usbManager.deviceList.values.toList()
            val matches = devices.filter {
                UsbDeviceMatcher.matches(it.vendorId, it.productId)
            }
            val previous = currentDevice
            val device = matches.firstOrNull { it.sameConnection(previous) }
                ?: matches.minByOrNull { it.deviceName }
            currentDevice = device

            if (device == null) {
                pendingRequest = null
                feedback = null
            } else if (!pendingRequest.matches(device)) {
                pendingRequest = null
                if (!device.sameConnection(previous)) feedback = null
            }

            val usbGranted = device?.let { usbManager.hasPermission(it) } ?: false
            if (usbGranted) pendingRequest = null
            val state = UsbStateResolver.resolve(
                hostSupported = true,
                devicePresent = device != null,
                cameraPermissionGranted = cameraGranted,
                usbPermissionGranted = usbGranted,
                permissionPending = pendingRequest != null,
            )
            if (state == UsbConnectionState.READY) feedback = null

            publish(
                UsbStatus(
                    state = state,
                    hostSupported = true,
                    deviceName = device?.deviceName,
                    productName = device?.productName,
                    vendorId = device?.vendorId,
                    productId = device?.productId,
                    cameraPermissionGranted = cameraGranted,
                    usbPermissionGranted = usbGranted,
                    message = feedback,
                ),
            )
            Log.i(
                TAG,
                "Scan: devices=${devices.size}, target=${device != null}, " +
                    "vidPid=${device?.let { "%04x:%04x".format(it.vendorId, it.productId) } ?: "none"}, " +
                    "camera=$cameraGranted, usb=$usbGranted, state=$state",
            )
        } catch (error: Exception) {
            currentDevice = null
            pendingRequest = null
            feedback = null
            publish(
                UsbStatus(
                    state = UsbConnectionState.ERROR,
                    hostSupported = true,
                    cameraPermissionGranted = cameraGranted,
                    message = "扫描 USB 设备失败，请重新连接后刷新。",
                ),
            )
            Log.e(TAG, "USB scan failed", error)
        }
    }

    /** A fresh permission check before handing the selected device to the video session. */
    fun readyDevice(): UsbDevice? {
        val device = currentDevice ?: return null
        val manager = usbManager ?: return null
        return device.takeIf {
            status.state == UsbConnectionState.READY && manager.hasPermission(it) &&
                context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestUsbPermission() {
        if (pendingRequest != null) {
            Log.i(TAG, "USB permission request already pending")
            return
        }
        refresh()
        val device = currentDevice ?: return
        val manager = usbManager ?: return
        if (status.state != UsbConnectionState.USB_PERMISSION_REQUIRED) return

        val nonce = UUID.randomUUID().toString()
        val request = PendingRequest(device.deviceName, device.deviceId, nonce)
        val intent = Intent(context, UsbPermissionReceiver::class.java).apply {
            action = ACTION_USB_PERMISSION
            data = Uri.parse("egouvcphone://usb-permission/$nonce")
            putExtra(EXTRA_REQUEST_NONCE, nonce)
        }
        val callback = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        pendingRequest = request
        feedback = null
        publish(status.copy(state = UsbConnectionState.USB_PERMISSION_PENDING, message = null))
        Log.i(TAG, "Requesting USB permission for 1bcf:0b15")
        try {
            manager.requestPermission(device, callback)
        } catch (error: Exception) {
            pendingRequest = null
            feedback = "申请 USB 权限失败，请重新连接后重试。"
            Log.e(TAG, "USB permission request failed", error)
            refresh()
        }
    }

    fun onCameraPermissionDenied() {
        feedback = "CAMERA 权限被拒绝；请允许后再申请 USB 权限。"
        Log.w(TAG, "CAMERA permission denied")
        refresh()
    }

    fun onPermissionResult(intent: Intent) {
        if (intent.action != ACTION_USB_PERMISSION) return
        val request = pendingRequest
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val nonce = intent.getStringExtra(EXTRA_REQUEST_NONCE)
        if (request == null || nonce != request.nonce || !request.matches(device) ||
            device == null || !UsbDeviceMatcher.matches(device.vendorId, device.productId)
        ) {
            Log.w(TAG, "Ignored unmatched USB permission callback")
            refresh()
            return
        }

        pendingRequest = null
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        Log.i(TAG, "USB permission callback: granted=$granted, vidPid=1bcf:0b15")
        refresh()
        if (currentDevice?.deviceName != request.deviceName ||
            currentDevice?.deviceId != request.deviceId
        ) {
            Log.i(TAG, "Permission result arrived after target disconnect or replacement")
            return
        }
        if (status.usbPermissionGranted) {
            feedback = null
            Log.i(TAG, "USB permission confirmed by hasPermission for 1bcf:0b15")
        } else if (!granted) {
            feedback = "USB 权限被拒绝；点击“申请权限”可重试。"
            Log.w(TAG, "USB permission denied for 1bcf:0b15")
        } else {
            feedback = "系统返回授权成功，但尚未确认设备访问权限；请刷新或重试。"
            Log.w(TAG, "USB permission callback granted but hasPermission=false")
        }
        publish(status.copy(message = feedback))
    }

    private fun publish(newStatus: UsbStatus) {
        status = newStatus
        observer?.invoke(newStatus)
    }

    private fun UsbDevice?.sameConnection(other: UsbDevice?): Boolean =
        this != null && other != null && deviceId == other.deviceId && deviceName == other.deviceName

    private fun PendingRequest?.matches(device: UsbDevice?): Boolean =
        this != null && device != null && deviceId == device.deviceId && deviceName == device.deviceName

    private data class PendingRequest(val deviceName: String, val deviceId: Int, val nonce: String)

    companion object {
        const val ACTION_USB_PERMISSION = "com.egouvc.phone.USB_PERMISSION"
        const val EXTRA_REQUEST_NONCE = "com.egouvc.phone.extra.REQUEST_NONCE"
        private const val TAG = "EgoUVCPhone"
    }
}
