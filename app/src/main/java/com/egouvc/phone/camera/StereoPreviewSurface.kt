package com.egouvc.phone.camera

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View

/** Keep the combined 3840×1080 frame's aspect ratio on the phone screen. */
class StereoPreviewSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * UvcMode.TARGET_HEIGHT / UvcMode.TARGET_WIDTH)
    }
}
