package com.egouvc.phone.recording

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android V0.2 raw episode:
 * - saved video bytes are copied from the libuvc MJPEG callback before decoding
 * - raw 252B UVC XU IMU packets AND decoded axes, subject to Android EP0 access
 * - host callback and USB control-transfer bounds on CLOCK_BOOTTIME
 *
 * No UI work, USB reads or disk writes occur on the libuvc frame callback thread.
 */
class EpisodeRecorder(
    private val context: Context,
    private val device: UsbDevice,
    private val width: Int,
    private val height: Int,
    private val streamingConnection: UsbDeviceConnection?,
    private val monitorConnection: UsbDeviceConnection?,
    private val onRecordingStatus: (Boolean, String) -> Unit,
) {
    private val controller = Executors.newSingleThreadExecutor { Thread(it, "EgoUVC-Episode-Control") }
    private val closed = AtomicBoolean(false)
    @Volatile private var active: Episode? = null
    init { require(width > 0 && height > 0) }

    fun start() = enqueue {
        if (active != null || closed.get()) return@enqueue
        var ep: Episode? = null
        try {
            val root = context.getExternalFilesDir("episodes") ?: error("没有应用外部文件目录")
            val dir = File(root, "episode_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}")
            check(dir.mkdirs()) { "创建 Episode 目录失败：$dir" }
            val started = Episode(dir, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
            ep = started
            // Explicitly start the video writer before announcing RECORDING.
            started.videoThread = Thread({ writeVideo(started) }, "EgoUVC-Episode-Video").apply { start() }
            started.imuThread = Thread({ pollImu(started) }, "EgoUVC-Episode-IMU").apply { start() }
            active = started
            onRecordingStatus(true, "采集中：原始 MJPEG 目标 ${TARGET_RECORDING_FPS} FPS，IMU XU 读取中… ${dir.absolutePath}")
        } catch (t: Exception) {
            Log.e(TAG, "Episode start failed", t)
            ep?.let { it.running.set(false); stopEpisode(it, "启动失败") }
            onRecordingStatus(false, "无法开始采集：${t.message}")
        }
    }

    fun stop() = enqueue {
        val ep = active ?: return@enqueue
        active = null
        stopEpisode(ep, "用户停止")
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        enqueue {
            val ep = active
            active = null
            if (ep != null) stopEpisode(ep, "相机关闭")
        }
        controller.shutdown()
    }

    /** Native libuvc MJPEG callback: select, copy and enqueue before the buffer expires. */
    fun onRawFrame(jpeg: ByteBuffer, callbackBootNs: Long, uvcSequence: Long,
                   frameWidth: Int, frameHeight: Int) {
        val ep = active ?: return
        if (!ep.running.get()) return
        val frameIndex = ep.callbackCount.incrementAndGet()
        if (!ep.sampler.select(callbackBootNs)) return
        ep.selectedFrames.incrementAndGet()
        try {
            val dup = jpeg.duplicate().apply { rewind() }
            val size = dup.remaining()
            if (frameWidth != width || frameHeight != height || size < 4 || size > MAX_JPEG_BYTES ||
                (dup.get(0).toInt() and 255) != 0xff || (dup.get(1).toInt() and 255) != 0xd8 ||
                (dup.get(size - 2).toInt() and 255) != 0xff || (dup.get(size - 1).toInt() and 255) != 0xd9) {
                ep.invalidFrames.incrementAndGet()
                return
            }
            if (ep.frameQueue.remainingCapacity() == 0) {
                ep.backpressureDropped.incrementAndGet()
                return
            }
            val raw = ByteArray(size)
            dup.get(raw)
            if (!ep.running.get()) return
            val data = Frame(raw, frameIndex, uvcSequence, callbackBootNs,
                SystemClock.elapsedRealtimeNanos())
            if (!ep.frameQueue.offer(data)) ep.backpressureDropped.incrementAndGet()
        } catch (t: Exception) {
            ep.invalidFrames.incrementAndGet()
            Log.w(TAG, "Failed to copy MJPEG frame", t)
        }
    }

    private fun writeVideo(ep: Episode) {
        try {
            BufferedOutputStream(FileOutputStream(File(ep.dir, "video.mjpg")), 1 shl 20).use { video ->
                File(ep.dir, "video_index.csv").bufferedWriter().use { csv ->
                    csv.write("frame_index,byte_offset,jpeg_bytes,frame_receive_boottime_ns,uvc_sequence\n")
                    var offset = 0L
                    while (ep.running.get() || ep.frameQueue.isNotEmpty()) {
                        val frame = ep.frameQueue.poll()
                        if (frame == null) { Thread.sleep(5); continue }
                        video.write(frame.raw)
                        csv.write("${frame.index},$offset,${frame.raw.size},${frame.bootNs},${frame.uvcSequence}\n")
                        offset += frame.raw.size
                        ep.savedBytes.set(offset)
                        ep.savedFrames.incrementAndGet()
                        val writeLatency = SystemClock.elapsedRealtimeNanos() - frame.queuedBootNs
                        ep.maxWriteLatencyNs.accumulateAndGet(writeLatency, ::maxOf)
                        if (ep.savedFrames.get() % 30L == 0L) { video.flush(); csv.flush() }
                    }
                    video.flush(); csv.flush()
                }
            }
        } catch (t: Exception) {
            ep.videoError = t.message ?: t.javaClass.simpleName
            ep.running.set(false)
            Log.e(TAG, "Episode video writer failed", t)
        }
    }

    private fun pollImu(ep: Episode) {
        try {
            XuImuReader(context, device, streamingConnection, monitorConnection).use { xu ->
                File(ep.dir, "imu_packets.bin").outputStream().buffered().use { raw ->
                    File(ep.dir, "imu_packets.csv").bufferedWriter().use { packets ->
                        File(ep.dir, "imu_samples.csv").bufferedWriter().use { samples ->
                            packets.write("packet_index,host_before_boottime_ns,host_after_boottime_ns,host_unix_ns,raw_offset_bytes,raw_bytes,first_counter,last_counter\n")
                            samples.write("packet_index,sample_index,host_before_boottime_ns,host_after_boottime_ns,host_unix_ns,counter_raw,ax_raw,ay_raw,az_raw,gx_raw,gy_raw,gz_raw\n")
                            var previous: ByteArray? = null
                            var lastCounter: Long? = null
                            var consecutiveErrors = 0
                            while (ep.running.get()) {
                                try {
                                    val packet = xu.readPacket()
                                    if (packet.raw.contentEquals(previous)) {
                                        ep.imuDuplicates.incrementAndGet()
                                    } else {
                                        consecutiveErrors = 0
                                        previous = packet.raw
                                        val first = packet.samples.first().counterRaw
                                        val last = lastCounter
                                        if (last != null) {
                                            val advance = (first - last) and 0xffff_ffffL
                                            if (advance > 32L && advance % 32L == 0L) {
                                                ep.imuMissingSamples.addAndGet(advance / 32L - 1L)
                                            }
                                        }
                                        lastCounter = packet.samples.last().counterRaw
                                        val number = ep.imuPackets.incrementAndGet()
                                        val offset = (number - 1L) * ImuPacketParser.PACKET_BYTES
                                        raw.write(packet.raw)
                                        packets.write("$number,${packet.beforeBootNs},${packet.afterBootNs},${packet.hostUnixNs},$offset,252,$first,${packet.samples.last().counterRaw}\n")
                                        packet.samples.forEachIndexed { i, s ->
                                            samples.write("$number,$i,${packet.beforeBootNs},${packet.afterBootNs},${packet.hostUnixNs},${s.counterRaw},${s.ax},${s.ay},${s.az},${s.gx},${s.gy},${s.gz}\n")
                                        }
                                        ep.imuSamples.addAndGet(packet.samples.size.toLong())
                                        if (number % 10L == 0L) {
                                            raw.flush(); packets.flush(); samples.flush()
                                        }
                                    }
                                } catch (e: Exception) {
                                    ep.imuReadErrors.incrementAndGet()
                                    consecutiveErrors++
                                    if (consecutiveErrors >= MAX_CONSECUTIVE_IMU_ERRORS) {
                                        ep.imuError = "XU GET_CUR 连续失败：${e.message}"
                                        break
                                    }
                                }
                                Thread.sleep(IMU_POLL_MS)
                            }
                            raw.flush(); packets.flush(); samples.flush()
                        }
                    }
                }
            }
        } catch (t: Exception) {
            ep.imuError = "无法读取 XU IMU：${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, "Episode IMU reader failed", t)
        }
    }

    /** Only runs on the coordinator thread, never on UI or libuvc callback. */
    private fun stopEpisode(ep: Episode, reason: String) {
        ep.running.set(false)
        try { ep.videoThread?.join() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        try { ep.imuThread?.join() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        val endBoot = SystemClock.elapsedRealtimeNanos()
        val endUnix = System.currentTimeMillis()
        try {
            val videoFileBytes = File(ep.dir, "video.mjpg").length()
            val imuFileBytes = File(ep.dir, "imu_packets.bin").length()
            if (videoFileBytes != ep.savedBytes.get()) ep.videoError = "video.mjpg 长度与索引不一致"
            if (imuFileBytes != ep.imuPackets.get() * ImuPacketParser.PACKET_BYTES)
                ep.imuError = "imu_packets.bin 长度与索引不一致"
            val complete = ep.videoError == null && ep.imuError == null &&
                ep.savedFrames.get() > 0 && ep.imuPackets.get() > 0
            val durationSec = (endBoot - ep.startedBootNs) / 1_000_000_000.0
            val savedFps = ep.savedFrames.get() / durationSec
            File(ep.dir, "metadata.json").writeText("""{
  "schema_version": "egouvc_phone_episode_v0_2_3",
  "status": "${if (complete) "COMPLETE" else "INCOMPLETE"}",
  "video_source": "libuvc MJPEG callback before preview decode; original compressed bytes",
  "video_format": "ORIGINAL_MJPEG",
  "requested_width": $width,
  "requested_height": $height,
  "requested_uvc_fps": 60,
  "target_saved_fps": $TARGET_RECORDING_FPS,
  "observed_saved_fps": ${String.format(Locale.US, "%.3f", savedFps)},
  "video_timestamp": "native libuvc callback CLOCK_BOOTTIME, not camera exposure",
  "timebase_video": "ANDROID_CLOCK_BOOTTIME_NS",
  "imu_timestamp": "host USB GET_CUR start/end CLOCK_BOOTTIME, not IMU sample capture time",
  "timebase_imu": "ANDROID_CLOCK_BOOTTIME_NS",
  "imu_unit": 3,
  "imu_selector": 1,
  "imu_record_format": "14 x [DE C8,u32BE counter,6xs16BE] per 252B packet",
  "imu_counter_unit": "unknown; no camera-IMU calibration or hardware sync",
  "start_boot_ns": ${ep.startedBootNs},
  "end_boot_ns": $endBoot,
  "started_unix_ms": ${ep.startedUnixMs},
  "ended_unix_ms": $endUnix,
  "stop_reason": ${toJsonString(reason)},
  "video_frames_saved": ${ep.savedFrames.get()},
  "imu_packets_saved": ${ep.imuPackets.get()},
  "complete": $complete
}
""")
            File(ep.dir, "quality.json").writeText("""{
  "raw_mjpeg_callback_frames": ${ep.callbackCount.get()},
  "video_frames_selected": ${ep.selectedFrames.get()},
  "video_frames_saved": ${ep.savedFrames.get()},
  "video_bytes_saved": ${ep.savedBytes.get()},
  "video_saved_fps": ${String.format(Locale.US, "%.3f", savedFps)},
  "max_video_queue_to_write_ns": ${ep.maxWriteLatencyNs.get()},
  "video_backpressure_dropped": ${ep.backpressureDropped.get()},
  "video_invalid_frames": ${ep.invalidFrames.get()},
  "video_error": ${toJsonString(ep.videoError)},
  "imu_packets": ${ep.imuPackets.get()},
  "imu_samples": ${ep.imuSamples.get()},
  "imu_duplicates": ${ep.imuDuplicates.get()},
  "imu_missing_samples_estimated": ${ep.imuMissingSamples.get()},
  "imu_read_errors": ${ep.imuReadErrors.get()},
  "imu_error": ${toJsonString(ep.imuError)}
}
""")
            val imuMsg = if (ep.imuError == null) "IMU ${ep.imuSamples.get()} 样本" else "IMU 失败：${ep.imuError}"
            onRecordingStatus(false, "${if (complete) "已保存" else "未完整保存"} ${ep.savedFrames.get()} 帧原始 MJPEG，" +
                String.format(Locale.US, "%.1f FPS", savedFps) + "，$imuMsg。位置：${ep.dir.absolutePath}")
        } catch (t: Exception) {
            Log.e(TAG, "Episode finalization failed", t)
            onRecordingStatus(false, "Episode 保存结束出错：${t.message}（目录：${ep.dir}）")
        }
    }

    private fun enqueue(work: () -> Unit) {
        try { controller.execute(work) } catch (_: RejectedExecutionException) { /* closed */ }
    }

    private class Episode(val dir: File, val startedUnixMs: Long, val startedBootNs: Long) {
        val running = AtomicBoolean(true)
        val sampler = RawFrameSampler(TARGET_RECORDING_FPS)
        val callbackCount = AtomicLong()
        val selectedFrames = AtomicLong()
        val frameQueue = ArrayBlockingQueue<Frame>(FRAME_QUEUE_CAPACITY)
        val savedFrames = AtomicLong()
        val savedBytes = AtomicLong()
        val maxWriteLatencyNs = AtomicLong()
        val backpressureDropped = AtomicLong()
        val invalidFrames = AtomicLong()
        val imuPackets = AtomicLong()
        val imuSamples = AtomicLong()
        val imuDuplicates = AtomicLong()
        val imuMissingSamples = AtomicLong()
        val imuReadErrors = AtomicLong()
        @Volatile var videoError: String? = null
        @Volatile var imuError: String? = null
        var videoThread: Thread? = null
        var imuThread: Thread? = null
    }

    private data class Frame(val raw: ByteArray, val index: Long, val uvcSequence: Long,
                             val bootNs: Long, val queuedBootNs: Long)

    private fun toJsonString(text: String?): String =
        if (text == null) "null" else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    companion object {
        private const val TAG = "EgoUVCPhone"
        private const val TARGET_RECORDING_FPS = 30
        private const val MAX_JPEG_BYTES = 8 * 1024 * 1024
        private const val FRAME_QUEUE_CAPACITY = 8
        private const val IMU_POLL_MS = 10L
        private const val MAX_CONSECUTIVE_IMU_ERRORS = 100
    }
}
