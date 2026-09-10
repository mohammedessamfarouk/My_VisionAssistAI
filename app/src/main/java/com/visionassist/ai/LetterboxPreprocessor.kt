package com.visionassist.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.min

class LetterboxPreprocessor(
    private val inputSize: Int
) {

    fun preprocess(bitmap: Bitmap): LetterboxResult {

        val scale = min(
            inputSize.toFloat() / bitmap.width,
            inputSize.toFloat() / bitmap.height
        )

        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            newWidth,
            newHeight,
            true
        )

        val output = Bitmap.createBitmap(
            inputSize,
            inputSize,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)

        canvas.drawColor(Color.rgb(114,114,114))

        val padX = (inputSize - newWidth) / 2f
        val padY = (inputSize - newHeight) / 2f

        canvas.drawBitmap(
            resized,
            padX,
            padY,
            null
        )

        val buffer =
            FloatBuffer.allocate(
                3 * inputSize * inputSize
            )

        val pixels =
            IntArray(inputSize * inputSize)

        output.getPixels(
            pixels,
            0,
            inputSize,
            0,
            0,
            inputSize,
            inputSize
        )

        for (p in pixels)
            buffer.put(((p shr 16) and 255) / 255f)

        for (p in pixels)
            buffer.put(((p shr 8) and 255) / 255f)

        for (p in pixels)
            buffer.put((p and 255) / 255f)

        buffer.rewind()

        return LetterboxResult(
            buffer,
            scale,
            padX,
            padY,
            bitmap.width,
            bitmap.height
        )
    }

}