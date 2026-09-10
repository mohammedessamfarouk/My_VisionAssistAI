package com.visionassist.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream

/**
 * بديل ميزة "التقاط صورة كل فترة زمنية". بيشتغل من أي شاشة فيها كاميرا،
 * وبياخد لقطة كل 10 دقايق بس، ويخزنها كنص Base64 مباشرة جوا Firestore
 * (بدل Firebase Storage اللي محتاج ترقية خطة الدفع Blaze).
 * بنصغّر الصورة كويس عشان تفضل تحت حد المستند الواحد في Firestore (1 ميجا).
 */
object PeriodicSnapshotManager {

    private const val INTERVAL_MS = 10 * 60 * 1000L // 10 دقايق
    private var lastCaptureTime = 0L

    // مقاس صغير كفاية يخلي حجم النص بعد الـ Base64 والضغط بعيد جدًا عن حد الـ 1 ميجا
    private const val MAX_DIMENSION = 480

    fun maybeCapture(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < INTERVAL_MS) return
        lastCaptureTime = now

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        try {
            val resized = resizeBitmap(bitmap, MAX_DIMENSION)

            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 55, stream)
            val bytes = stream.toByteArray()

            Log.d("PeriodicSnapshot", "DIAGNOSTIC: jpeg bytes = ${bytes.size}")

            val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val doc = mapOf(
                "imageBase64" to base64Image,
                "timestamp" to now
            )

            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("snapshots").document(now.toString())
                .set(doc)
                .addOnSuccessListener {
                    Log.d("PeriodicSnapshot", "DIAGNOSTIC: snapshot saved at $now (${base64Image.length} chars)")
                }
                .addOnFailureListener { e ->
                    Log.e("PeriodicSnapshot", "DIAGNOSTIC: firestore write failed", e)
                }

        } catch (e: Exception) {
            Log.e("PeriodicSnapshot", "DIAGNOSTIC: capture error", e)
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}