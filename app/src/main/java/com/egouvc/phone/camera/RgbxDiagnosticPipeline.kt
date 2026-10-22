package com.egouvc.phone.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded diagnostic path for the RGBX callback of UVCAndroid 1.0.13.
 *
 * Important: These are DECODED pixels. Saved JPEG images are re-encoded samples,
 * never the original MJPEG bitstream or a valid 60 FPS production episode.
 * This path should be replaced by native uvc_frame MJPEG capture in V0.2.
 */
class RgbxDiagnosticPipeline(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val onPreview: (Bitmap) -> Unit,
    private val onRecordingStatus: (Boolean, String) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EgoUVC-RGBX-Diagnostic")
    }
    private val pending = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val expectedBytes = width.toLong() * height * 4
    @Volatile private var lastSubmittedNs = 0L
    // Only accessed on executor thread.
    private var recording: Episode? = null

    init {
        require(width > 0 && height > 0 && expectedBytes <= Int.MAX_VALUE)
    }

    /** Called on libuvc frame callback thread: never retain the native ByteBuffer. */
    fun onFrame(rgbx: ByteBuffer, bootNs: Long, callbackSequence: Long) {
        if (closed.get() || bootNs - lastSubmittedNs < SAMPLE_PERIOD_NS) return
        if (!pending.compareAndSet(false, true)) return
        val copy = try {
            val frame = rgbx.duplicate().apply { rewind() }
            if (frame.remaining() < expectedBytes.toInt()) {
                Log.w(TAG, "Short RGBX callback: ${frame.remaining()} < $expectedBytes")
                pending.set(false)
                return
            }
            ByteArray(expectedBytes.toInt()).also { frame.get(it) }
        } catch (t: Throwable) {
            pending.set(false)
            Log.w(TAG, "Failed to copy native RGBX buffer", t)
            return
        }
        lastSubmittedNs = bootNs
        try {
            executor.execute {
                try {
                    process(copy, bootNs, callbackSequence)
                } catch (t: Throwable) {
                    Log.e(TAG, "Diagnostic image processing failed", t)
                    recording?.let { finishEpisode(it, "录制出错：${t.message}") }
                } finally {
                    pending.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.set(false)
        }
    }

    fun startRecording() = enqueue {
        if (recording != null) return@enqueue
        try {
            val root = context.getExternalFilesDir("episodes")
                ?: error("应用外部存储不可用")
            val name = "debug_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}"
            val dir = File(root, name)
            val frames = File(dir, "frames")
            check(frames.mkdirs()) { "无法创建录制目录 $frames" }
            val index = File(dir, "frames.csv").bufferedWriter()
            index.write("callback_sequence,host_callback_boottime_ns,filename,jpeg_bytes\n")
            val episode = Episode(dir, frames, index, System.currentTimeMillis())
            recording = episode
            onRecordingStatus(true, "正在诊断采集（最多约 4 FPS，重编码 JPEG）：$dir")
        } catch (t: Throwable) {
            Log.e(TAG, "Start diagnostic capture failed", t)
            onRecordingStatus(false, "无法开始采集：${t.message}")
        }
    }

    fun stopRecording() = enqueue {
        recording?.let { finishEpisode(it, null) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        enqueue {
            recording?.let { finishEpisode(it, "关闭相机，诊断采集已结束") }
        }
        executor.shutdown()
    }

    private fun enqueue(block: () -> Unit) {
        try { executor.execute(block) }
        catch (_: RejectedExecutionException) { /* already shut down */ }
    }

    private fun process(bytes: ByteArray, bootNs: Long, sequence: Long) {
        // libuvc's PIXEL_FORMAT_RGBX is R,G,B,X: X is NOT guaranteed opaque.
        // Android ARGB_8888 uses RGBA byte order on little-endian Android devices.
        for (i in 3 until bytes.size step 4) bytes[i] = 0xff.toByte()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            recording?.let { ep ->
                val filename = "frame_${sequence.toString().padStart(8, '0')}.jpg"
                val file = File(ep.framesDir, filename)
                file.outputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out))
                    out.flush()
                }
                ep.index.write("$sequence,$bootNs,frames/$filename,${file.length()}\n")
                ep.index.flush()
                ep.count++
            }
            // Only sampled preview; native UVC frame callback still runs at full rate.
            val previewWidth = minOf(width, MAX_PREVIEW_WIDTH)
            val previewHeight = (height.toLong() * previewWidth / width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, previewWidth, previewHeight, true)
            onPreview(scaled)
        } finally {
            bitmap.recycle()
        }
    }

    private fun finishEpisode(ep: Episode, note: String?) {
        try {
            ep.index.close()
            File(ep.dir, "metadata.json").writeText(
                """{
                  "schema": "egouvc_phone_debug_jpeg_v1",
                  "source": "DECODED_RGBX_REENCODED_JPEG_NOT_ORIGINAL_MJPEG",
                  "requested_width": $width,
                  "requested_height": $height,
                  "sample_limit_fps": 4,
                  "host_clock": "android.os.SystemClock.elapsedRealtimeNanos / CLOCK_BOOTTIME",
                  "host_timestamp_semantics": "callback_observation_not_exposure",
                  "started_unix_ms": ${ep.startedUnixMs},
                  "ended_unix_ms": ${System.currentTimeMillis()},
                  "saved_frames": ${ep.count},
                  "complete": ${note == null},
                  "notes": "diagnostic only; no IMU; not raw video"
                }""".trimIndent() + "\n"
            )
            val message = note ?: "已保存 ${ep.count} 帧诊断 JPEG：${ep.dir.absolutePath}"
            onRecordingStatus(false, message)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to finalize diagnostic episode", t)
            onRecordingStatus(false, "诊断文件结束失败：${t.message}")
        } finally {
            recording = null
        }
    }

    private class Episode(
        val dir: File,
        val framesDir: File,
        val index: BufferedWriter,
        val startedUnixMs: Long,
        var count: Int = 0,
    )

    companion object {
        private const val TAG = "EgoUVCPhone"
        private const val SAMPLE_PERIOD_NS = 250_000_000L
        private const val MAX_PREVIEW_WIDTH = 960
        private const val JPEG_QUALITY = 85
    }
}
