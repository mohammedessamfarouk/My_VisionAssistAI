package com.visionassist.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * بديل load_emotion_model/predict_emotion/predict_emotion_smoothed في الكود البايثون الأصلي.
 * الشكل الافتراضي (زي FER2013 الكلاسيكي): صورة 48x48 رمادية (grayscale)، 7 مشاعر.
 * بنفحص الشكل الحقيقي وقت التشغيل زي ما عملنا مع MobileFaceNet، عشان لو مختلف نلاقيه فورًا.
 */
class EmotionEngine(context: Context, modelAssetName: String = "emotion_model.tflite") {

    companion object {
        val EMOTION_LABELS = listOf("angry", "disgust", "fear", "happy", "neutral", "sad", "surprise")
        const val MIN_CONFIDENCE = 0.5f
        const val SMOOTH_WINDOW = 5
    }

    private val interpreter: Interpreter
    private var inputSize = 48
    private var isGrayscale = true

    // بديل _emotion_history في الكود الأصلي - تخزين آخر كذا نتيجة لكل وش عشان الـ smoothing
    private val emotionHistory = mutableMapOf<String, MutableList<String>>()

    init {
        val model = loadModelFile(context, modelAssetName)
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })

        val inputShape = interpreter.getInputTensor(0).shape() // [1, H, W, C] غالبًا
        val inputType = interpreter.getInputTensor(0).dataType()
        val outputShape = interpreter.getOutputTensor(0).shape()

        Log.d("EmotionEngine", "DIAGNOSTIC: input shape=${inputShape.toList()} type=$inputType")
        Log.d("EmotionEngine", "DIAGNOSTIC: output shape=${outputShape.toList()}")

        if (inputShape.size == 4) {
            // بنفترض NHWC: [batch, height, width, channels]
            inputSize = inputShape[1]
            isGrayscale = inputShape[3] == 1
        }
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val fd = context.assets.openFd(assetName)
        val stream = FileInputStream(fd.fileDescriptor)
        val channel = stream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /** بديل predict_emotion() - بيرجع (اسم المشاعر, نسبة الثقة) أو ("", 0) لو مش واثق كفاية */
    fun predict(faceBitmap: Bitmap): Pair<String, Float> {
        val resized = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        val input = bitmapToByteBuffer(resized)

        val output = Array(1) { FloatArray(EMOTION_LABELS.size) }
        interpreter.run(input, output)

        val scores = output[0]
        var bestIdx = 0
        var bestScore = scores[0]
        for (i in scores.indices) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }

        return if (bestScore >= MIN_CONFIDENCE && bestIdx < EMOTION_LABELS.size) {
            EMOTION_LABELS[bestIdx] to bestScore
        } else {
            "" to bestScore
        }
    }

    /** بديل predict_emotion_smoothed() - بياخد أغلب نتيجة في آخر 5 قراءات لنفس الوش (faceId) */
    fun predictSmoothed(faceBitmap: Bitmap, faceId: String): Pair<String, Float> {
        val (label, confidence) = predict(faceBitmap)
        if (label.isEmpty() || confidence < MIN_CONFIDENCE) return "" to confidence

        val history = emotionHistory.getOrPut(faceId) { mutableListOf() }
        history.add(label)
        if (history.size > SMOOTH_WINDOW) history.removeAt(0)

        val mostCommon = if (history.size >= 3) {
            history.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: label
        } else label

        return mostCommon to confidence
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val channels = if (isGrayscale) 1 else 3
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * channels * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            if (isGrayscale) {
                // نفس معادلة التحويل لرمادي المستخدمة عادة (luminosity)
                val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                buffer.putFloat(gray)
            } else {
                buffer.putFloat(r / 255f)
                buffer.putFloat(g / 255f)
                buffer.putFloat(b / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    fun close() = interpreter.close()
}