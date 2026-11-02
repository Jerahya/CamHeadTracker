package io.github.jerahya.camheadtracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FaceWireframeView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val pointPaint = Paint().apply {
        color = Color.rgb(80, 220, 140)
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val framePaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val imageRect = RectF()
    private var screenPoints = FloatArray(0)

    @Volatile var showImage = true
    @Volatile var showPoints = true

    @Volatile private var image: Bitmap? = null
    @Volatile private var landmarks: FloatArray? = null
    @Volatile private var imageWidth = 3
    @Volatile private var imageHeight = 4

    fun update(image: Bitmap?, landmarks: FloatArray?, imageWidth: Int, imageHeight: Int) {
        this.image = image
        this.landmarks = landmarks
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(20, 20, 20))

        val scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val w = imageWidth * scale
        val h = imageHeight * scale
        val left = (width - w) / 2
        val top = (height - h) / 2
        imageRect.set(left, top, left + w, top + h)

        val bitmap = image
        if (showImage && bitmap != null) {
            canvas.save()
            canvas.scale(-1f, 1f, imageRect.centerX(), imageRect.centerY())
            canvas.drawBitmap(bitmap, null, imageRect, null)
            canvas.restore()
        }
        canvas.drawRect(imageRect, framePaint)

        val points = landmarks
        if (!showPoints || points == null)
            return
        if (screenPoints.size != points.size)
            screenPoints = FloatArray(points.size)
        for (i in points.indices step 2) {
            screenPoints[i] = left + (1 - points[i]) * w
            screenPoints[i + 1] = top + points[i + 1] * h
        }
        canvas.drawPoints(screenPoints, pointPaint)
    }
}
