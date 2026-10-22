package com.egouvc.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.SurfaceHolder
import com.egouvc.phone.camera.CameraPhase
import com.egouvc.phone.camera.CameraStatus
import com.egouvc.phone.camera.UvcCameraSource
import com.egouvc.phone.databinding.ActivityMainBinding
import com.egouvc.phone.usb.UsbConnectionState
import com.egouvc.phone.usb.UsbDeviceManager
import com.egouvc.phone.usb.UsbStatus
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private val deviceManager: UsbDeviceManager
        get() = (application as EgoUvcApplication).usbDeviceManager
    private val statusObserver: (UsbStatus) -> Unit = ::renderStatus
    private var cameraRequestPending = false
    private lateinit var cameraSource: UvcCameraSource
    private var previewSurfaceReady = false
    private var cameraPhase = CameraPhase.IDLE
    private var recording = false
    private var cameraSourceIsProbing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.refreshButton.setOnClickListener { deviceManager.refresh() }
        binding.requestPermissionButton.setOnClickListener { requestNeededPermission() }
        cameraSource = UvcCameraSource(applicationContext, ::renderCamera, ::showDecodedPreview)
        binding.probeImuButton.setOnClickListener { cameraSource.probeImu() }
        binding.decodedPreviewImage.onRenderedFps = { fps ->
            binding.previewFpsValue.text = java.lang.String.format(
                Locale.US, "抽样预览实际绘制：%.1f FPS（不含底层 SurfaceView）", fps,
            )
        }
        binding.startCaptureButton.setOnClickListener { cameraSource.startEpisodeRecording() }
        binding.stopCaptureButton.setOnClickListener { cameraSource.stopEpisodeRecording() }
        binding.openCameraButton.setOnClickListener {
            deviceManager.refresh()
            val device = deviceManager.readyDevice()
            if (device == null) {
                binding.cameraMessageValue.text = getString(R.string.video_requires_ready)
            } else if (previewSurfaceReady) {
                cameraSource.open(device, binding.previewSurface.holder.surface)
            } else {
                binding.cameraMessageValue.text = getString(R.string.preview_surface_unavailable)
            }
        }
        binding.closeCameraButton.setOnClickListener { cameraSource.close() }
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurfaceReady = true
                updateCameraButtons()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewSurfaceReady = false
                cameraSource.close()
                updateCameraButtons()
            }
        })
        Log.i(TAG, "MainActivity created")
    }

    override fun onStart() {
        super.onStart()
        deviceManager.observe(statusObserver)
    }

    override fun onResume() {
        super.onResume()
        deviceManager.refresh()
    }

    override fun onStop() {
        cameraSource.close()
        deviceManager.removeObserver(statusObserver)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.i(TAG, "Target attach intent received")
            deviceManager.refresh()
        }
    }

    private fun requestNeededPermission() {
        when (deviceManager.status.state) {
            UsbConnectionState.CAMERA_PERMISSION_REQUIRED -> {
                if (cameraRequestPending) return
                cameraRequestPending = true
                binding.requestPermissionButton.isEnabled = false
                Log.i(TAG, "Requesting CAMERA permission for USB video device")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
            }
            UsbConnectionState.USB_PERMISSION_REQUIRED -> deviceManager.requestUsbPermission()
            else -> Unit
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST_CODE) return
        cameraRequestPending = false
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i(TAG, "CAMERA permission granted")
            deviceManager.refresh()
            deviceManager.requestUsbPermission()
        } else {
            deviceManager.onCameraPermissionDenied()
        }
    }

    private fun renderCamera(status: CameraStatus) {
        cameraPhase = status.phase
        recording = status.recording
        cameraSourceIsProbing = status.imuProbing
        status.recordingMessage?.let { binding.recordingStatusValue.text = it }
        if (status.phase == CameraPhase.IDLE || status.phase == CameraPhase.ERROR) {
            binding.decodedPreviewImage.clearFrame()
            binding.decodedPreviewImage.visibility = View.INVISIBLE
        }
        binding.cameraStateValue.text = when (status.phase) {
            CameraPhase.IDLE -> getString(R.string.video_idle)
            CameraPhase.OPENING -> getString(R.string.video_opening)
            CameraPhase.STREAMING -> getString(R.string.video_streaming)
            CameraPhase.ERROR -> getString(R.string.state_error)
        }
        binding.cameraModeValue.text = status.configuredMode ?: getString(R.string.not_available)
        binding.cameraFpsValue.text = if (status.rawCallbackFps != null || status.callbackFps != null) {
            java.lang.String.format(Locale.US,
                "原始 MJPEG 回调 %.1f FPS / %d 帧；解码回调 %.1f FPS / %d 帧",
                status.rawCallbackFps ?: 0.0, status.rawFramesObserved,
                status.callbackFps ?: 0.0, status.framesObserved)
        } else getString(R.string.video_fps_waiting)
        binding.cameraModesValue.text = status.supportedModes ?: getString(R.string.not_available)
        binding.cameraMessageValue.text = status.message.orEmpty()
        binding.imuProbeValue.text = status.imuProbeMessage ?: "IMU：尚未探测"
        updateCameraButtons()
    }

    private fun showDecodedPreview(bitmap: Bitmap) {
        if (cameraPhase != CameraPhase.STREAMING) {
            bitmap.recycle()
            return
        }
        binding.decodedPreviewImage.showFrame(bitmap)
        binding.decodedPreviewImage.visibility = View.VISIBLE
    }

    private fun updateCameraButtons() {
        if (!::binding.isInitialized) return
        binding.openCameraButton.isEnabled = previewSurfaceReady &&
            deviceManager.status.state == UsbConnectionState.READY &&
            (cameraPhase == CameraPhase.IDLE || cameraPhase == CameraPhase.ERROR)
        binding.closeCameraButton.isEnabled = cameraPhase == CameraPhase.OPENING ||
            cameraPhase == CameraPhase.STREAMING
        binding.startCaptureButton.isEnabled = cameraPhase == CameraPhase.STREAMING && !recording
        binding.stopCaptureButton.isEnabled = cameraPhase == CameraPhase.STREAMING && recording
        binding.probeImuButton.isEnabled = cameraPhase == CameraPhase.STREAMING && !recording &&
            !cameraSourceIsProbing
    }

    private fun renderStatus(status: UsbStatus) {
        if (status.state != UsbConnectionState.READY && cameraPhase != CameraPhase.IDLE &&
            cameraPhase != CameraPhase.ERROR
        ) cameraSource.close()
        binding.deviceNameValue.text = when {
            status.productName != null && status.deviceName != null ->
                "${status.productName}\n${status.deviceName}"
            else -> status.deviceName ?: getString(R.string.not_available)
        }
        binding.vidPidValue.text = if (status.vendorId != null && status.productId != null) {
            String.format(Locale.US, "%04x:%04x", status.vendorId, status.productId)
        } else {
            getString(R.string.not_available)
        }
        binding.hostValue.setText(if (status.hostSupported) R.string.supported else R.string.unsupported)
        binding.connectionValue.setText(
            when (status.state) {
                UsbConnectionState.DISCONNECTED -> R.string.state_disconnected
                UsbConnectionState.CAMERA_PERMISSION_REQUIRED -> R.string.state_camera_required
                UsbConnectionState.USB_PERMISSION_REQUIRED -> R.string.state_usb_required
                UsbConnectionState.USB_PERMISSION_PENDING -> R.string.state_usb_pending
                UsbConnectionState.READY -> R.string.state_ready
                UsbConnectionState.ERROR -> R.string.state_error
            },
        )
        binding.cameraPermissionValue.setText(
            if (status.cameraPermissionGranted) R.string.granted else R.string.not_granted,
        )
        binding.usbPermissionValue.setText(
            when {
                status.deviceName == null -> R.string.no_target_device
                status.usbPermissionGranted -> R.string.granted
                else -> R.string.not_granted
            },
        )
        binding.requestPermissionButton.isEnabled = !cameraRequestPending &&
            (status.state == UsbConnectionState.CAMERA_PERMISSION_REQUIRED ||
                status.state == UsbConnectionState.USB_PERMISSION_REQUIRED)
        binding.messageValue.text = status.message.orEmpty()
        binding.messageValue.visibility = if (status.message.isNullOrBlank()) View.GONE else View.VISIBLE
        updateCameraButtons()
    }

    companion object {
        private const val TAG = "EgoUVCPhone"
        private const val CAMERA_REQUEST_CODE = 1001
    }
}
