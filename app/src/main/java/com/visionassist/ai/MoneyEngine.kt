package com.visionassist.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * نفس فكرة YoloEngine بالظبط بس لموديل الفلوس (money.onnx / money_labels.txt).
 * فيها نفس إصلاح letterbox اللي عملناه في الأوبجكت ديتكشن.
 */
class MoneyEngine(private val context: Context) {

    companion object {
        const val INPUT_SIZE = 384 // نفس imgsz الأصلي بتاع money_model في الكود البايثون
        const val CONFIDENCE_THRESHOLD = 0.55f // نفس conf=0.55 في money_mode الأصلي
        const val IOU_THRESHOLD = 0.45f
    }

    private lateinit var environment: OrtEnvironment
    private lateinit var session: OrtSession
    val labels = mutableListOf<String>()
    private val preprocessor = LetterboxPreprocessor(INPUT_SIZE)
    val inputSize: Int get() = INPUT_SIZE

    private var hasLoggedDiagnostics = false

    init {
        loadLabels()
        loadModel()
    }

    private fun loadLabels() {
        try {
            context.assets.open("money_labels.txt").bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) labels.add(line) }
            }
            Log.d("MoneyEngine", "DIAGNOSTIC: labels loaded = ${labels.size} -> $labels")
        } catch (e: Exception) {
            Log.e("MoneyEngine", "DIAGNOSTIC: Label Error (money_labels.txt) - ${e.message}")
        }
    }

    private fun loadModel() {
        try {
            environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val model = context.assets.open("money.onnx").readBytes()
            session = environment.createSession(model, options)
            Log.d("MoneyEngine", "DIAGNOSTIC: outputInfo = ${session.outputInfo}")
        } catch (e: Exception) {
            Log.e("MoneyEngine", "DIAGNOSTIC: Model Error (money.onnx) - ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val letterbox = preprocessor.preprocess(bitmap)
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(environment, letterbox.buffer, shape)

        try {
            session.run(mapOf(inputName to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val raw = results[0].value as Array<Array<FloatArray>>
                return postProcess(raw[0], letterbox)
            }
        } finally {
            tensor.close()
        }
    }

    private fun postProcess(rawOutput: Array<FloatArray>, letterbox: LetterboxResult): List<Detection> {
        val numClasses = rawOutput.size - 4
        val numBoxes = rawOutput[0].size

        if (!hasLoggedDiagnostics) {
            hasLoggedDiagnostics = true
            Log.d("MoneyEngine", "DIAGNOSTIC: rawOutput channels = ${rawOutput.size}")
            Log.d("MoneyEngine", "DIAGNOSTIC: numClasses computed = $numClasses")
            Log.d("MoneyEngine", "DIAGNOSTIC: labels.size = ${labels.size}")
            // لو الرقمين دول مش متطابقين، دي مصدر مشكلة "بيقول class_N بدل اسم الفئة"
        }

        val candidates = mutableListOf<Detection>()

        for (b in 0 until numBoxes) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = rawOutput[4 + c][b]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < CONFIDENCE_THRESHOLD) continue

            val cx = rawOutput[0][b]
            val cy = rawOutput[1][b]
            val w = rawOutput[2][b]
            val h = rawOutput[3][b]

            var left = cx - w / 2f
            var top = cy - h / 2f
            var right = cx + w / 2f
            var bottom = cy + h / 2f

            // شيل تأثير الـ letterbox padding والـ scale - نفس إصلاح YoloEngine بالظبط
            left = (left - letterbox.padX) / letterbox.scale
            top = (top - letterbox.padY) / letterbox.scale
            right = (right - letterbox.padX) / letterbox.scale
            bottom = (bottom - letterbox.padY) / letterbox.scale

            left = left.coerceIn(0f, letterbox.originalWidth.toFloat())
            top = top.coerceIn(0f, letterbox.originalHeight.toFloat())
            right = right.coerceIn(0f, letterbox.originalWidth.toFloat())
            bottom = bottom.coerceIn(0f, letterbox.originalHeight.toFloat())

            val label = labels.getOrElse(bestClass) { "class_$bestClass" }
            if (label.startsWith("class_")) {
                Log.w("MoneyEngine", "DIAGNOSTIC WARNING: bestClass=$bestClass لكن labels.size=${labels.size} - فيه مشكلة في money_labels.txt")
            }

            candidates.add(
                Detection(
                    classId = bestClass,
                    label = label,
                    score = bestScore,
                    left = left, top = top, right = right, bottom = bottom
                )
            )
        }
        return nonMaxSuppression(candidates)
    }

    private fun nonMaxSuppression(boxes: List<Detection>): List<Detection> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best, it) > IOU_THRESHOLD }
        }
        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    fun close() {
        if (::session.isInitialized) session.close()
        if (::environment.isInitialized) environment.close()
    }
}