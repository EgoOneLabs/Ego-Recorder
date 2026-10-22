package com.egouvc.phone.camera

/** Format 7 is UVC VS_FRAME_MJPEG. The target is one combined stereo image. */
data class UvcMode(val formatType: Int, val width: Int, val height: Int, val fps: List<Int>) {
    val description: String
        get() = "${if (formatType == MJPEG_TYPE) "MJPEG" else "UVC type $formatType"} " +
            "${width}×${height} @ ${fps.joinToString("/")} FPS"

    companion object {
        const val MJPEG_TYPE = 7
        const val TARGET_WIDTH = 3840
        const val TARGET_HEIGHT = 1080
        const val TARGET_FPS = 60
    }
}

object UvcModeSelector {
    fun target(modes: List<UvcMode>): UvcMode? = modes.firstOrNull {
        it.formatType == UvcMode.MJPEG_TYPE && it.width == UvcMode.TARGET_WIDTH &&
            it.height == UvcMode.TARGET_HEIGHT && UvcMode.TARGET_FPS in it.fps
    }
}
