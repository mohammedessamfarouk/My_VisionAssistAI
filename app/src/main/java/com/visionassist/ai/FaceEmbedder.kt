package com.visionassist.ai

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * يحول صورة وش (112x112) لبصمة رقمية (192 رقم) باستخدام MobileFaceNet.
 * تأكدنا من الشكل الحقيقي وقت الـ runtime: المدخل صورة واحدة بس [1,112,112,3]
 * (مش batch=2 زي ما كنا مفترضين قبل كده - ده كان سبب الكراش).
 */
class FaceEmbedder(context: Context, modelAssetName: String = "MobileFaceNet.tflite") {

    companion object {
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
    }

    private val interpreter: Interpreter

    init {
        val model = loadModelFile(context, modelAssetName)
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })

        val inputShape = interpreter.getInputTensor(0).shape().toList()
        val inputType = interpreter.getInputTensor(0).dataType()
        val outputShape = interpreter.getOutputTensor(0).shape().toList()
        val outputType = interpreter.getOutputTensor(0).dataType()
        android.util.Log.d("FaceEmbedder", "DIAGNOSTIC: input shape=$inputShape type=$inputType")
        android.util.Log.d("FaceEmbedder", "DIAGNOSTIC: output shape=$outputShape type=$outputType")
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val fd = context.assets.openFd(assetName)
        val stream = FileInputStream(fd.fileDescriptor)
        val channel = stream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /** بيرجع بصمة الوش (192 رقم) - يشتغل على صورة الوش بعد ما تتقص من الصورة الأصلية */
    fun getEmbedding(faceBitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToByteBuffer(resized)

        val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
        interpreter.run(input, output)
        return output[0]
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) - 127.5f
            val g = ((pixel shr 8) and 0xFF) - 127.5f
            val b = (pixel and 0xFF) - 127.5f
            buffer.putFloat(r / 128f)
            buffer.putFloat(g / 128f)
            buffer.putFloat(b / 128f)
        }
        buffer.rewind()
        return buffer
    }

    fun close() = interpreter.close()
}

/** مقارنة بصمتين - كل ما القيمة أقرب لـ 1.0 كل ما الوشين أشبه ببعض */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA <= 0f || normB <= 0f) return 0f
    return dot / (sqrt(normA) * sqrt(normB))
}