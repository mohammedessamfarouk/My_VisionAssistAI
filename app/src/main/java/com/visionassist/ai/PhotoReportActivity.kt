package com.visionassist.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شاشة ولي الأمر لعرض كل اللقطات الدورية (كل 10 دقايق) بتاعة الأعمى، مرتبة بالأحدث الأول.
 * الصور مخزنة كـ Base64 جوا Firestore نفسه (مش رابط تحميل خارجي).
 */
class PhotoReportActivity : AppCompatActivity() {

    private lateinit var photosContainer: LinearLayout
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_report)

        photosContainer = findViewById(R.id.photosContainer)

        val linkedBlindUid = getSharedPreferences("guardian_prefs", MODE_PRIVATE)
            .getString("linkedBlindUid", null)

        if (linkedBlindUid == null) {
            addMessage("No linked blind user found. Link first from the Guardian screen.")
            return
        }

        loadPhotos(linkedBlindUid)
    }

    private fun loadPhotos(blindUid: String) {
        addMessage("Loading photos...")

        db.collection("users").document(blindUid)
            .collection("snapshots")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                photosContainer.removeAllViews()

                if (snapshot.isEmpty) {
                    addMessage("No photos yet. Photos are captured automatically every 10 minutes while the blind user is using the app.")
                    return@addOnSuccessListener
                }

                for (doc in snapshot.documents) {
                    val base64Image = doc.getString("imageBase64") ?: continue
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    addPhotoRow(base64Image, timestamp)
                }
            }
            .addOnFailureListener { e ->
                Log.e("PhotoReportActivity", "DIAGNOSTIC: failed to load photos", e)
                photosContainer.removeAllViews()
                addMessage("Error loading photos: ${e.message}")
            }
    }

    private fun addMessage(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 32)
        }
        photosContainer.addView(tv)
    }

    private fun addPhotoRow(base64Image: String, timestamp: Long) {
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val txtTime = TextView(this).apply {
            text = timeText
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }

        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFDDDDDD.toInt())
        }

        try {
            val bytes = Base64.decode(base64Image, Base64.NO_WRAP)
            val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("PhotoReportActivity", "DIAGNOSTIC: failed to decode image", e)
        }

        rowLayout.addView(txtTime)
        rowLayout.addView(imageView)
        photosContainer.addView(rowLayout)
    }
}