// File: AspectRatioSurfaceView.kt
package net.mikecarr.demultiplixator

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.widget.FrameLayout
import kotlin.math.roundToInt

class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val surfaceView: SurfaceView
    private var videoAspectRatio: Float = 16f / 9f

    init {
        surfaceView = SurfaceView(context)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setVideoDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoAspectRatio = width.toFloat() / height.toFloat()
        requestLayout() // Trigger a re-measure
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (parentWidth == 0 || parentHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val parentAspectRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val finalWidth: Int
        val finalHeight: Int

        if (parentAspectRatio > videoAspectRatio) {
            // Parent is wider than the video. Height is the constraint.
            finalHeight = parentHeight
            finalWidth = (finalHeight * videoAspectRatio).roundToInt()
        } else {
            // Parent is taller than the video. Width is the constraint.
            finalWidth = parentWidth
            finalHeight = (finalWidth / videoAspectRatio).roundToInt()
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        )
    }
}