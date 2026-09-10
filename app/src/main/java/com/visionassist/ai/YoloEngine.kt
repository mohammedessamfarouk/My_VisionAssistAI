package com.visionassist.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class YoloEngine(private val context: Context) {

    companion object {
        const val INPUT_SIZE = 640
        const val CONFIDENCE_THRESHOLD = 0.35f
        const val IOU_THRESHOLD = 0.45f
    }

    private lateinit var environment: OrtEnvironment
    private lateinit var session: OrtSession

    val labels = mutableListOf<String>()

    private val preprocessor = LetterboxPreprocessor(INPUT_SIZE)

    val inputSize: Int
        get() = INPUT_SIZE

    init {
        loadLabels()
        loadModel()
    }

    private fun loadLabels() {
        try {
            context.assets.open("coco_labels.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) labels.add(line)
                    }
                }
            Log.d("YOLO", "Labels Loaded = ${labels.size}")
        } catch (e: Exception) {
            Log.e("YOLO", "Label Error", e)
        }
    }

    private fun loadModel() {
        try {
            environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)

            val model = context.assets.open("yolov8n.onnx").readBytes()
            session = environment.createSession(model, options)

            Log.d("YOLO", "InputInfo = ${session.inputInfo}")
            Log.d("YOLO", "OutputInfo = ${session.outputInfo}")
            Log.d("YOLO", "Model Loaded Successfully")
        } catch (e: Exception) {
            Log.e("YOLO", "Model Error", e)
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val letterbox = preprocessor.preprocess(bitmap)
        val inputBuffer = letterbox.buffer

        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(environment, inputBuffer, shape)

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

    /**
     * الإصلاح الأساسي: الصندوق طالع من الموديل في مساحة 640x640 المبطنة (letterboxed).
     * لازم نرجع نشيل تأثير الـ padding والـ scale عشان نوصل لمكان الجسم الحقيقي
     * في الصورة الأصلية (bitmap.width x bitmap.height)، وإلا الصندوق يترسم في مكان غلط.
     */
    private fun postProcess(
        rawOutput: Array<FloatArray>,
        letterbox: LetterboxResult
    ): List<Detection> {

        val numClasses = rawOutput.size - 4
        val numBoxes = rawOutput[0].size
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

            // الصندوق في المساحة المبطنة (640x640)
            var left = cx - w / 2f
            var top = cy - h / 2f
            var right = cx + w / 2f
            var bottom = cy + h / 2f

            // شيل الـ padding واقسم على الـ scale = ارجع لمقاس الصورة الأصلية
            left = (left - letterbox.padX) / letterbox.scale
            top = (top - letterbox.padY) / letterbox.scale
            right = (right - letterbox.padX) / letterbox.scale
            bottom = (bottom - letterbox.padY) / letterbox.scale

            // تقييد الصندوق جوا حدود الصورة الأصلية (يمنع صناديق طايرة برة الشاشة)
            left = left.coerceIn(0f, letterbox.originalWidth.toFloat())
            top = top.coerceIn(0f, letterbox.originalHeight.toFloat())
            right = right.coerceIn(0f, letterbox.originalWidth.toFloat())
            bottom = bottom.coerceIn(0f, letterbox.originalHeight.toFloat())

            Log.d(
                "YOLO_CLASS",
                "id=$bestClass label=${labels.getOrElse(bestClass) { "Unknown" }} score=$bestScore"
            )

            candidates.add(
                Detection(
                    classId = bestClass,
                    label = labels.getOrElse(bestClass) { "Unknown" },
                    score = bestScore,
                    left = left, top = top, right = right, bottom = bottom
                )
            )
        }

        Log.d("YOLO", "Candidates = ${candidates.size}")
        val result = nonMaxSuppression(candidates)
        Log.d("YOLO", "Final = ${result.size}")
        return result
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

        if (union <= 0f) return 0f
        return intersection / union
    }

    fun close() {
        if (::session.isInitialized) session.close()
        if (::environment.isInitialized) environment.close()
    }
}