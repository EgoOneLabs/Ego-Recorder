package com.egouvc.phone.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.egouvc.phone.usb.UsbDeviceMatcher
import com.egouvc.phone.recording.EpisodeRecorder
import com.egouvc.phone.recording.XuImuReader
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.IRawFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.util.Locale

/** Owns one libuvc session. Public status callbacks run on the main thread. */
class UvcCameraSource(
    private val context: Context,
    private val onStatus: (CameraStatus) -> Unit,
    private val onDecodedPreview: (Bitmap) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private data class Session(
        val id: Int,
        val device: UsbDevice,
        val surface: Surface,
        val thread: HandlerThread,
        val worker: Handler,
        var monitor: USBMonitor? = null,
        var camera: UVCCamera? = null,
        var monitorConnection: android.hardware.usb.UsbDeviceConnection? = null,
        var pipeline: RgbxDiagnosticPipeline? = null,
        @Volatile var recorder: EpisodeRecorder? = null,
        var openingStarted: Boolean = false,
        @Volatile var lastFrameBootNs: Long = 0,
    )
    private var active: Session? = null
    @Volatile private var generation = 0
    private var status = CameraStatus(CameraPhase.IDLE)

    fun open(device: UsbDevice, previewSurface: Surface) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (status.phase != CameraPhase.IDLE && status.phase != CameraPhase.ERROR) return
        close()
        if (!UsbDeviceMatcher.matches(device.vendorId, device.productId) || !previewSurface.isValid) {
            publish(CameraStatus(CameraPhase.ERROR, message = "目标设备或预览 Surface 不可用。"))
            return
        }
        val thread = HandlerThread("EgoUVC-Camera").also { it.start() }
        val session = Session(++generation, device, previewSurface, thread, Handler(thread.looper))
        active = session
        publish(CameraStatus(CameraPhase.OPENING, message = "正在打开 UVC 相机…"))
        try {
            val usbMonitor = USBMonitor(context.applicationContext, object : USBMonitor.OnDeviceConnectListener {
                override fun onAttach(device: UsbDevice?) = Unit
                override fun onDetach(device: UsbDevice?) {
                    if (isTarget(session.id, device)) fail(session.id, "相机已拔出。")
                }
                override fun onDeviceOpen(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
                    if (!isTarget(session.id, device) || ctrlBlock == null || session.openingStarted) return
                    session.openingStarted = true
                    session.worker.post { openCamera(session, ctrlBlock) }
                }
                override fun onDeviceClose(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                    if (isTarget(session.id, device)) fail(session.id, "USB 相机会话已关闭。")
                }
                override fun onCancel(device: UsbDevice?) {
                    if (isTarget(session.id, device)) fail(session.id, "UVC 设备打开失败或权限失效。")
                }
                override fun onError(device: UsbDevice?, error: USBMonitor.USBException?) {
                    if (isTarget(session.id, device)) fail(session.id, "UVC USB 打开失败：${error?.message}")
                }
            })
            session.monitor = usbMonitor
            usbMonitor.register()
            // V0.1 already requested CAMERA and USB. This library call only obtains its
            // UsbControlBlock; hasPermission() should take the no-dialog path.
            if (!usbMonitor.hasPermission(device)) {
                fail(session.id, "USB 权限已失效，请刷新设备并重新授权。")
                return
            }
            session.worker.post {
                if (session.id == generation) usbMonitor.requestPermission(device)
            }
            Log.i(TAG, "UVC open requested: 1bcf:0b15 device=${device.deviceName}")
        } catch (error: Exception) {
            Log.e(TAG, "UVC monitor failed", error)
            fail(session.id, "UVC 初始化失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun openCamera(session: Session, control: USBMonitor.UsbControlBlock) {
        if (session.id != generation) return
        var opened: UVCCamera? = null
        try {
            opened = UVCCamera(null)
            val result = opened.open(control)
            check(result == 0) { "libuvc open returned $result" }
            val modes = opened.supportedSizeList.orEmpty().map {
                UvcMode(it.type, it.width, it.height, (it.fpsList ?: emptyList()).plus(it.fps).distinct())
            }.distinct()
            val modeText = modes.joinToString("\n") { it.description }.ifBlank { "设备未返回 UVC 模式" }
            Log.i(TAG, "UVC supported modes:\n$modeText")
            val selected = UvcModeSelector.target(modes)
                ?: error("设备未声明 MJPEG 3840×1080 @ 60 FPS。")
            // setPreviewSize validates descriptors and native stream control. No format fallback.
            opened.setPreviewSize(
                selected.width, selected.height,
                UVCCamera.UVC_VS_FRAME_MJPEG, UvcMode.TARGET_FPS,
            )
            val negotiated = opened.previewSize
            check(negotiated != null && negotiated.type == UvcMode.MJPEG_TYPE &&
                negotiated.width == selected.width && negotiated.height == selected.height &&
                negotiated.fps == UvcMode.TARGET_FPS
            ) { "libuvc 返回的模式与请求不一致：$negotiated" }
            if (session.id != generation) return
            session.camera = opened
            session.monitorConnection = control.connection
            val meter = FrameRateMeter()
            // UVCAndroid delivers RGBX, NOT the original MJPEG bytes. The sampled
            // pipeline provides a reliable preview and explicitly diagnostic capture.
            val pipeline = RgbxDiagnosticPipeline(context, selected.width, selected.height,
                onPreview = { bmp ->
                    main.post {
                        if (session.id == generation) onDecodedPreview(bmp) else bmp.recycle()
                    }
                },
                onRecordingStatus = { isRecording, message ->
                    main.post {
                        if (session.id == generation) publish(status.copy(
                            recording = isRecording, recordingMessage = message,
                        ))
                    }
                },
            )
            session.pipeline = pipeline
            val recorder = EpisodeRecorder(context, session.device, selected.width, selected.height,
                streamingConnection = opened.usbControlBlock?.connection,
                monitorConnection = control.connection,
                onRecordingStatus = { isRecording, message ->
                    main.post {
                        if (session.id == generation) publish(status.copy(
                            recording = isRecording, recordingMessage = message,
                        ))
                    }
                },
            )
            session.recorder = recorder
            val rawMeter = FrameRateMeter()
            opened.setRawFrameCallback(IRawFrameCallback { jpeg, receiveBootNs, uvcSequence, frameWidth, frameHeight ->
                val rawFps = rawMeter.onFrame(receiveBootNs)
                recorder.onRawFrame(jpeg, receiveBootNs, uvcSequence, frameWidth, frameHeight)
                if (rawFps != null) {
                    val count = rawMeter.totalFrames
                    main.post {
                    if (session.id == generation) {
                        publish(status.copy(rawCallbackFps = rawFps, rawFramesObserved = count))
                        Log.i(TAG, "Raw MJPEG callback FPS=%.1f, frames=%d".format(
                            Locale.US, rawFps, count))
                    }
                    }
                }
            })
            // This public callback receives decoded RGBX. It is only a delivered-frame FPS probe;
            // raw MJPEG ingress must be added at the native uvc_frame callback in the next milestone.
            opened.setFrameCallback(IFrameCallback { frame: ByteBuffer ->
                val now = SystemClock.elapsedRealtimeNanos()
                session.lastFrameBootNs = now
                val fps = meter.onFrame(now)
                pipeline.onFrame(frame, now, meter.totalFrames)
                if (meter.totalFrames == 1L || fps != null) {
                    main.post {
                        if (session.id == generation) {
                            publish(status.copy(
                                phase = CameraPhase.STREAMING,
                                configuredMode = "请求 MJPEG ${negotiated.width}×${negotiated.height} @ ${negotiated.fps} FPS",
                                supportedModes = modeText,
                                callbackFps = fps ?: status.callbackFps,
                                framesObserved = meter.totalFrames,
                                message = null,
                            ))
                            if (fps != null) Log.i(TAG, "Decoded frame callback FPS=%.1f, frames=%d".format(Locale.US, fps, meter.totalFrames))
                        }
                    }
                }
            }, UVCCamera.PIXEL_FORMAT_RGBX)
            val display = session.surface
            check(display.isValid) { "预览 Surface 已释放" }
            opened.setPreviewDisplay(display)
            opened.startPreview()
            main.post {
                if (session.id == generation) {
                    if (status.phase == CameraPhase.OPENING) {
                        publish(status.copy(
                            configuredMode = "请求 MJPEG ${negotiated.width}×${negotiated.height} @ ${negotiated.fps} FPS",
                            supportedModes = modeText,
                            message = "已请求 MJPEG 流，等待首帧…",
                        ))
                    }
                    main.postDelayed({
                        if (session.id == generation && status.phase == CameraPhase.OPENING) {
                            fail(session.id, "5 秒内未收到视频帧，请检查供电与 UVC 日志。")
                        }
                    }, 5_000)
                    monitorFrameStall(session)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "UVC preview failed", error)
            main.post { fail(session.id, "UVC 打开失败：${error.message ?: error.javaClass.simpleName}") }
        } finally {
            if (session.camera !== opened) {
                try { opened?.destroy() } catch (error: Exception) { Log.w(TAG, "Camera cleanup failed", error) }
            }
        }
    }

    private fun monitorFrameStall(session: Session) {
        main.postDelayed({
            if (session.id != generation) return@postDelayed
            val last = session.lastFrameBootNs
            if (last != 0L && SystemClock.elapsedRealtimeNanos() - last > 3_000_000_000L) {
                fail(session.id, "视频帧超过 3 秒未更新；流已停止。")
            } else {
                monitorFrameStall(session)
            }
        }, 1_000)
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++
        val session = active
        active = null
        publish(CameraStatus(CameraPhase.IDLE))
        session?.worker?.post {
            try {
                try { session.camera?.setRawFrameCallback(null) }
                catch (error: Exception) { Log.w(TAG, "Raw callback cleanup failed", error) }
                try { session.camera?.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_RGBX) }
                catch (error: Exception) { Log.w(TAG, "Frame callback cleanup failed", error) }
                try { session.camera?.stopPreview() }
                catch (error: Exception) { Log.w(TAG, "Preview stop failed", error) }
                session.camera?.destroy()
            } catch (error: Exception) {
                Log.w(TAG, "UVC cleanup failed", error)
            } finally {
                session.recorder?.close()
                session.pipeline?.close()
                session.camera = null
                try { session.monitor?.destroy() } catch (error: Exception) { Log.w(TAG, "Monitor cleanup failed", error) }
                session.thread.quitSafely()
            }
        }
    }

    /** Start bounded original MJPEG + raw XU IMU episode. */
    fun startEpisodeRecording() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (status.phase != CameraPhase.STREAMING || status.recording) return
        active?.recorder?.start()
    }

    fun stopEpisodeRecording() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active?.recorder?.stop()
    }

    /** One diagnostic XU read while streaming, without creating an Episode. */
    fun probeImu() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val session = active ?: return
        if (status.phase != CameraPhase.STREAMING || status.recording) return
        publish(status.copy(imuProbeMessage = "正在探测 XU IMU…", imuProbing = true))
        session.worker.post {
            val result = try {
                val streaming = session.camera?.usbControlBlock?.connection
                XuImuReader(context, session.device, streaming, session.monitorConnection).use { reader ->
                    val packet = reader.readPacket()
                    "IMU 成功：${packet.raw.size} B，${packet.samples.size} 组；计数器 ${packet.samples.first().counterRaw}…${packet.samples.last().counterRaw}"
                }
            } catch (error: Exception) {
                Log.e("EgoUVC-XU", "Standalone IMU probe failed", error)
                "IMU 探测失败：${error.message ?: error.javaClass.simpleName}"
            }
            main.post {
                if (session.id == generation) publish(status.copy(imuProbeMessage = result, imuProbing = false))
            }
        }
    }

    private fun fail(session: Int, message: String) {
        if (session != generation) return
        Log.e(TAG, message)
        close()
        publish(CameraStatus(CameraPhase.ERROR, message = message))
    }

    private fun isTarget(session: Int, device: UsbDevice?): Boolean =
        session == generation && device != null && device.deviceName == active?.device?.deviceName &&
            device.deviceId == active?.device?.deviceId

    private fun publish(value: CameraStatus) {
        status = value
        onStatus(value)
    }

    companion object { private const val TAG = "EgoUVCPhone" }
}

enum class CameraPhase { IDLE, OPENING, STREAMING, ERROR }

data class CameraStatus(
    val phase: CameraPhase,
    val configuredMode: String? = null,
    val supportedModes: String? = null,
    val callbackFps: Double? = null,
    val framesObserved: Long = 0,
    val rawCallbackFps: Double? = null,
    val rawFramesObserved: Long = 0,
    val message: String? = null,
    val recording: Boolean = false,
    val recordingMessage: String? = null,
    val imuProbeMessage: String? = null,
    val imuProbing: Boolean = false,
)
