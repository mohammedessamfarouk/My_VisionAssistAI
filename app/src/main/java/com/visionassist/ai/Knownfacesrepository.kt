package com.visionassist.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

data class KnownFace(val name: String, val embedding: FloatArray)

/**
 * بديل load_faces() في الكود البايثون الأصلي - دلوقتي بيدعم أكتر من صورة لكل شخص.
 * التنظيم المطلوب: assets/faces/اسم_الشخص/1.jpg, 2.jpg, 3.jpg ...
 * كل صورة بتتحول لبصمة منفصلة، ووقت المقارنة بناخد أفضل تطابق من أي صورة مسجلة للشخص ده.
 */
class KnownFacesRepository(
    private val context: Context,
    private val faceEmbedder: FaceEmbedder
) {
    val knownFaces = mutableListOf<KnownFace>()

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    fun loadKnownFaces(folder: String = "faces") {
        val entries = try {
            context.assets.list(folder) ?: emptyArray()
        } catch (e: Exception) {
            Log.e("KnownFaces", "Cannot list assets/$folder", e)
            emptyArray()
        }

        Log.d("KnownFaces", "DIAGNOSTIC: entries found in assets/$folder = ${entries.toList()}")

        for (entryName in entries) {
            val entryPath = "$folder/$entryName"
            val innerFiles = try { context.assets.list(entryPath) } catch (e: Exception) { null }

            if (innerFiles.isNullOrEmpty()) {
                // مش فولدر (أو فاضي) - نتعامل معاه كصورة واحدة مباشرة (توافق مع التنظيم القديم)
                loadSinglePhoto(folder, entryName, personNameOverride = null)
            } else {
                // فولدر فيه أكتر من صورة لنفس الشخص
                val personName = entryName
                var loadedCount = 0
                for (photoName in innerFiles) {
                    if (loadSinglePhoto(entryPath, photoName, personNameOverride = personName)) {
                        loadedCount++
                    }
                }
                Log.d("KnownFaces", "DIAGNOSTIC: $personName -> اتحمل منه $loadedCount صورة من ${innerFiles.size}")
            }
        }

        Log.d("KnownFaces", "DIAGNOSTIC: إجمالي البصمات المسجلة = ${knownFaces.size} لعدد أشخاص = ${knownFaces.map { it.name }.distinct().size}")
    }

    /** بترجع true لو نجحت في تحميل الصورة واستخراج بصمة منها */
    private fun loadSinglePhoto(folderPath: String, fileName: String, personNameOverride: String?): Boolean {
        val lower = fileName.lowercase()
        if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) return false

        return try {
            val stream = context.assets.open("$folderPath/$fileName")
            val bitmap = BitmapFactory.decodeStream(stream)
            stream.close()

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(inputImage))

            if (faces.isEmpty()) {
                Log.w("KnownFaces", "مفيش وش اتلاقى في $folderPath/$fileName - اتجاهلت")
                return false
            }

            val box = faces[0].boundingBox
            val leftEyePoint = faces[0].getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEyePoint = faces[0].getLandmark(FaceLandmark.RIGHT_EYE)?.position

            Log.d("KnownFaces", "DIAGNOSTIC: $folderPath/$fileName box=$box leftEye=$leftEyePoint rightEye=$rightEyePoint")

            val faceBitmap = FaceAligner.alignAndCrop(bitmap, box, leftEyePoint, rightEyePoint)
            val embedding = faceEmbedder.getEmbedding(faceBitmap)
            val name = personNameOverride ?: fileName.substringBeforeLast(".")

            knownFaces.add(KnownFace(name, embedding))
            Log.d("KnownFaces", "اتحمل بنجاح: $name (من $fileName)")
            true

        } catch (e: Exception) {
            Log.e("KnownFaces", "فشل تحميل $folderPath/$fileName", e)
            false
        }
    }

    /**
     * بيرجع أفضل شخص (أعلى تطابق عبر كل صوره المسجلة) لو فوق حد التشابه، وإلا "Unknown".
     */
    fun recognize(embedding: FloatArray, threshold: Float = 0.5f): Pair<String, Float> {
        val bestPerName = mutableMapOf<String, Float>()

        for (known in knownFaces) {
            val score = cosineSimilarity(embedding, known.embedding)
            val current = bestPerName[known.name] ?: -1f
            if (score > current) bestPerName[known.name] = score
        }

        val logLine = bestPerName.entries.joinToString(" ") { "${it.key}=${"%.3f".format(it.value)}" }
        Log.d("FaceRecognition", "DIAGNOSTIC ALL SCORES: $logLine")

        val sorted = bestPerName.entries.sortedByDescending { it.value }
        if (sorted.isEmpty()) return "Unknown" to -1f

        val best = sorted[0]
        return if (best.value >= threshold) best.key to best.value else "Unknown" to best.value
    }

    fun close() = detector.close()
}