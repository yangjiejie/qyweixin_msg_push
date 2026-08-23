package com.videowatch.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.AttributeSet
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer

/**
 * 支持缩放和画面比例调节的视频视图
 * 参考主流App做法：FIT(适应)、CROP(裁剪)、STRETCH(拉伸)三种比例模式
 */
class ZoomableVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        const val ASPECT_FIT = 0      // 适应：保持比例完整显示，可能有黑边
        const val ASPECT_CROP = 1      // 裁剪：保持比例填满画面，可能裁切
        const val ASPECT_STRETCH = 2   // 拉伸：拉伸填满画面，可能变形
    }

    val textureView: TextureView = TextureView(context)

    private var minScale = 0.5f
    private var maxScale = 3f
    private var currentScale = 1f

    private var videoWidth = 0
    private var videoHeight = 0
    private var aspectRatioMode = ASPECT_FIT

    private var translationX = 0f
    private var translationY = 0f
    private val lastTouchPoint = PointF()
    private var isPanning = false

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    init {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.CENTER
        addView(textureView, lp)
        textureView.pivotX = 0f
        textureView.pivotY = 0f
        textureView.scaleX = 1f
        textureView.scaleY = 1f

        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, DoubleTapListener())
    }

    fun setPlayer(player: ExoPlayer) {
        player.setVideoTextureView(textureView)
    }

    fun setVideoSize(width: Int, height: Int) {
        if (videoWidth != width || videoHeight != height) {
            videoWidth = width
            videoHeight = height
            requestLayout()
        }
    }

    fun setAspectRatioMode(mode: Int) {
        if (aspectRatioMode != mode) {
            aspectRatioMode = mode
            if (aspectRatioMode == ASPECT_STRETCH) {
                val lp = textureView.layoutParams as LayoutParams
                lp.width = LayoutParams.MATCH_PARENT
                lp.height = LayoutParams.MATCH_PARENT
                textureView.layoutParams = lp
            }
            resetZoom()
            requestLayout()
        }
    }

    fun setZoomRange(min: Float, max: Float) {
        minScale = min
        maxScale = max
    }

    fun zoomIn() {
        applyScale((currentScale + 0.25f).coerceAtMost(maxScale), width / 2f, height / 2f)
    }

    fun zoomOut() {
        applyScale((currentScale - 0.25f).coerceAtLeast(minScale), width / 2f, height / 2f)
    }

    fun resetZoom() {
        textureView.scaleX = 1f
        textureView.scaleY = 1f
        textureView.translationX = 0f
        textureView.translationY = 0f
        currentScale = 1f
        translationX = 0f
        translationY = 0f
    }

    fun getCurrentScale(): Float = currentScale

    fun getBitmap(): Bitmap? = captureFrame()

    fun captureFrame(): Bitmap? {
        return try {
            textureView.bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据视频分辨率和当前比例模式重新测量TextureView大小
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (videoWidth > 0 && videoHeight > 0 && aspectRatioMode != ASPECT_STRETCH) {
            val viewWidth = measuredWidth
            val viewHeight = measuredHeight
            if (viewWidth > 0 && viewHeight > 0) {
                val videoAspect = videoWidth.toFloat() / videoHeight
                val viewAspect = viewWidth.toFloat() / viewHeight

                val childWidth: Int
                val childHeight: Int

                if (aspectRatioMode == ASPECT_FIT) {
                    if (videoAspect > viewAspect) {
                        childWidth = viewWidth
                        childHeight = (viewWidth / videoAspect).toInt()
                    } else {
                        childHeight = viewHeight
                        childWidth = (viewHeight * videoAspect).toInt()
                    }
                } else { // ASPECT_CROP
                    if (videoAspect > viewAspect) {
                        childHeight = viewHeight
                        childWidth = (viewHeight * videoAspect).toInt()
                    } else {
                        childWidth = viewWidth
                        childHeight = (viewWidth / videoAspect).toInt()
                    }
                }

                textureView.measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
                )
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            scaleGestureDetector.onTouchEvent(it)
            gestureDetector.onTouchEvent(it)

            when (it.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchPoint.set(it.x, it.y)
                    isPanning = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (it.pointerCount == 1 && currentScale > 1.01f && !isPanning) {
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * 修复触摸事件：不再无脑返回true消耗所有事件，让单击能正确传递
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        var handled = false
        event?.let { ev ->
            val isScaleHandled = scaleGestureDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchPoint.set(ev.x, ev.y)
                    handled = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1 && currentScale > 1.01f) {
                        val dx = ev.x - lastTouchPoint.x
                        val dy = ev.y - lastTouchPoint.y
                        translationX += dx
                        translationY += dy
                        textureView.translationX = translationX
                        textureView.translationY = translationY
                        lastTouchPoint.set(ev.x, ev.y)
                        clampTranslation()
                        handled = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                    if (isScaleHandled) handled = true
                }
            }
        }
        if (!handled) {
            return super.onTouchEvent(event)
        }
        return true
    }

    private fun applyScale(newScale: Float, pivotX: Float, pivotY: Float) {
        val scaleRatio = newScale / currentScale
        val px = pivotX - translationX
        val py = pivotY - translationY
        translationX = pivotX - px * scaleRatio
        translationY = pivotY - py * scaleRatio

        textureView.scaleX = newScale
        textureView.scaleY = newScale
        textureView.translationX = translationX
        textureView.translationY = translationY
        currentScale = newScale
        clampTranslation()
    }

    private fun clampTranslation() {
        if (currentScale <= 1f) {
            translationX = 0f
            translationY = 0f
            textureView.translationX = 0f
            textureView.translationY = 0f
            return
        }

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scaledW = viewW * currentScale
        val scaledH = viewH * currentScale

        // 允许向两侧平移，使缩放后视频尽量保持居中
        val maxTx = (scaledW - viewW).coerceAtLeast(0f) / 2f
        val maxTy = (scaledH - viewH).coerceAtLeast(0f) / 2f

        translationX = translationX.coerceIn(-maxTx, maxTx)
        translationY = translationY.coerceIn(-maxTy, maxTy)
        textureView.translationX = translationX
        textureView.translationY = translationY
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            applyScale(newScale, detector.focusX, detector.focusY)
            return true
        }
    }

    private inner class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.1f) {
                resetZoom()
            } else {
                applyScale(2f, e.x, e.y)
            }
            return true
        }
    }
}
