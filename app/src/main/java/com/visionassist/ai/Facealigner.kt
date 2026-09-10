package com.visionassist.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.atan2

/**
 * محاذاة الوش قبل ما يتبعت لـ MobileFaceNet.
 * موديلات الـ face embedding حساسة جدًا لميل الراس - لو مش هنظبطه، الدقة بتقل جدًا
 * حتى لو نفس الشخص بالظبط (ده كان سبب الـ 17% تشابه).
 */
object FaceAligner {

    fun alignAndCrop(
        sourceBitmap: Bitmap,
        box: Rect,
        leftEye: PointF?,
        rightEye: PointF?,
        outputSize: Int = 112
    ): Bitmap {

        var rotated = sourceBitmap

        if (leftEye != null && rightEye != null) {
            val dy = rightEye.y - leftEye.y
            val dx = rightEye.x - leftEye.x
            val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

            val centerX = box.centerX().toFloat()
            val centerY = box.centerY().toFloat()

            val matrix = Matrix().apply { postRotate(-angleDeg, centerX, centerY) }
            rotated = Bitmap.createBitmap(
                sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true
            )
        }

        val left = box.left.coerceIn(0, rotated.width - 1)
        val top = box.top.coerceIn(0, rotated.height - 1)
        val width = box.width().coerceAtMost(rotated.width - left)
        val height = box.height().coerceAtMost(rotated.height - top)

        if (width <= 0 || height <= 0) {
            return Bitmap.createScaledBitmap(sourceBitmap, outputSize, outputSize, true)
        }

        val cropped = Bitmap.createBitmap(rotated, left, top, width, height)
        return Bitmap.createScaledBitmap(cropped, outputSize, outputSize, true)
    }
}