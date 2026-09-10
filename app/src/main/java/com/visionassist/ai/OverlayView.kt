package com.visionassist.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var refWidth: Int = 640
    private var refHeight: Int = 640

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.YELLOW
    }

    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 42f
        style = Paint.Style.FILL
    }

    private val textBgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /**
     * refWidth/refHeight = المقاس اللي إحداثيات الصناديق (Detection) متحسوبة عليه بالظبط.
     * لو الصناديق جايالك من ObjectDetectionActivity بعد تصحيح letterbox، ابعت bitmap.width/height.
     * لو من شاشة لسه بتشتغل بمساحة الموديل المربعة (Currency/Navigation القدام)، سيب refHeight فاضية
     * وهي هتاخد نفس قيمة refWidth تلقائيًا (توافق مع الكود القديم).
     */
    fun setResults(newDetections: List<Detection>, refW: Int, refH: Int = refW) {
        detections = newDetections
        refWidth = refW
        refHeight = refH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val scaleX = width.toFloat() / refWidth
        val scaleY = height.toFloat() / refHeight

        for (det in detections) {
            val left = det.left * scaleX
            val top = det.top * scaleY
            val right = det.right * scaleX
            val bottom = det.bottom * scaleY

            val color = det.boxColor ?: Color.YELLOW
            boxPaint.color = color
            textPaint.color = color

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${det.label} ${(det.score * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(left, top - 50f, left + textWidth + 16f, top, textBgPaint)
            canvas.drawText(label, left + 8f, top - 12f, textPaint)
        }
    }
}