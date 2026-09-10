package com.visionassist.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer

class ImagePreprocessor(
    private val inputSize: Int = 640
) {

    data class Result(
        val buffer: FloatBuffer,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    fun preprocess(bitmap: Bitmap): Result {

        val scale = minOf(
            inputSize.toFloat() / bitmap.width,
            inputSize.toFloat() / bitmap.height
        )

        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        val resized =
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

        val output =
            Bitmap.createBitmap(
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
            FloatBuffer.allocate(3 * inputSize * inputSize)

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

        for(channel in 0..2){

            for(pixel in pixels){

                val value = when(channel){

                    0 -> (pixel shr 16) and 0xff

                    1 -> (pixel shr 8) and 0xff

                    else -> pixel and 0xff

                }

                buffer.put(value / 255f)

            }

        }

        buffer.rewind()

        return Result(
            buffer,
            scale,
            padX,
            padY
        )
    }

}