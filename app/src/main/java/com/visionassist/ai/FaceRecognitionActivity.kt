package com.visionassist.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * بديل face_mode() في الكود البايثون الأصلي.
 * فيها تعرف حقيقي على الهوية: ML Kit (يلاقي الوش) -> FaceEmbedder/MobileFaceNet (يستخرج بصمة)
 * -> KnownFacesRepository (يقارن) + مشاعر (EmotionEngine).
 *
 * ============================================================
 * تحديث: المصدر بقى كاميرا ESP32-CAM الخارجية (بدل كاميرا التليفون)
 * + مسافة حقيقية من حساس VL53L1X لو الوش في نص الشاشة.
 * ============================================================
 */
class FaceRecognitionActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var txtResult: TextView

    private lateinit var ttsManager: TTSManager
    private lateinit var faceEmbedder: FaceEmbedder
    private lateinit var knownFacesRepository: KnownFacesRepository
    private lateinit var emotionEngine: EmotionEngine

    private val streamReader = Esp32CamStreamReader()
    private val distanceSensorReader = DistanceSensorReader()
    @Volatile private var latestSensorDistanceMm: Int? = null

    // غيّر الـ IPs دول لو اتغيروا على شبكتك
    private val esp32CamIp = "192.168.1.3"
    private val distanceSensorIp = "192.168.1.17"

    private val liveDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    private val processingExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isProcessing = false

    // نفس فكرة last_face / last_face_time في الكود الأصلي
    private var lastAnnouncedFace = ""
    private var lastFaceTime = 0L
    private val faceCooldownMs = 6000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_recognition)

        imageView = findViewById(R.id.imageView)
        overlayView = findViewById(R.id.overlayView)
        txtResult = findViewById(R.id.txtResult)

        ttsManager = TTSManager(this)
        txtResult.text = "Loading known faces..."

        Executors.newSingleThreadExecutor().execute {
            try {
                faceEmbedder = FaceEmbedder(this)
                knownFacesRepository = KnownFacesRepository(this, faceEmbedder)
                knownFacesRepository.loadKnownFaces()
                emotionEngine = EmotionEngine(this)

                runOnUiThread {
                    txtResult.text = "Loaded ${knownFacesRepository.knownFaces.size} known faces"
                    connectToCamera()
                    startDistanceSensor()
                }
            } catch (e: Throwable) {
                Log.e("FaceRecognition", "DIAGNOSTIC: فشل تحميل الموديل/الوشوش", e)
                runOnUiThread {
                    txtResult.text = "Error: ${e.javaClass.simpleName} - ${e.message}"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::knownFacesRepository.isInitialized) {
            connectToCamera()
            startDistanceSensor()
        }
    }

    override fun onPause() {
        super.onPause()
        streamReader.stop()
        distanceSensorReader.stop()
    }

    private fun connectToCamera() {
        val url = "http://$esp32CamIp:81/stream"
        streamReader.start(
            streamUrl = url,
            onFrame = { bitmap ->
                runOnUiThread { imageView.setImageBitmap(bitmap) }
                processFrame(bitmap)
            },
            onError = { e ->
                runOnUiThread { txtResult.text = "Camera error: ${e.message}" }
            }
        )
    }

    private fun startDistanceSensor() {
        distanceSensorReader.start(
            ip = distanceSensorIp,
            onDistance = { mm -> latestSensorDistanceMm = mm },
            onError = { e -> Log.e("FaceRecognition", "DIAGNOSTIC: distance sensor error", e) }
        )
    }

    private fun processFrame(bitmap: Bitmap) {
        if (isProcessing || !::knownFacesRepository.isInitialized) return
        isProcessing = true

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        liveDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                try {
                    if (faces.isEmpty()) {
                        runOnUiThread {
                            overlayView.setResults(emptyList(), bitmap.width, bitmap.height)
                            txtResult.text = "No face detected"
                        }
                        isProcessing = false
                        return@addOnSuccessListener
                    }

                    PeriodicSnapshotManager.maybeCapture(bitmap)

                    val detections = mutableListOf<Detection>()
                    var topName = "Unknown"
                    var topEmotion = ""

                    for ((i, face) in faces.withIndex()) {
                        val box = face.boundingBox
                        val left = max(0, box.left)
                        val top = max(0, box.top)
                        val width = min(bitmap.width - left, box.width())
                        val height = min(bitmap.height - top, box.height())

                        if (width <= 0 || height <= 0) continue

                        val leftEyePoint = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                        val rightEyePoint = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                        val faceBitmap = FaceAligner.alignAndCrop(bitmap, box, leftEyePoint, rightEyePoint)
                        val embedding = faceEmbedder.getEmbedding(faceBitmap)
                        val (name, score) = knownFacesRepository.recognize(embedding)
                        topName = name

                        val rawFaceCrop = Bitmap.createBitmap(bitmap, left, top, width, height)
                        val (emotion, _) = emotionEngine.predictSmoothed(rawFaceCrop, faceId = "face_$i")
                        if (emotion.isNotEmpty()) topEmotion = emotion

                        detections.add(
                            Detection(
                                classId = 0,
                                label = if (emotion.isNotEmpty()) "$name ($emotion)" else name,
                                score = score.coerceIn(0f, 1f),
                                left = left.toFloat(),
                                top = top.toFloat(),
                                right = (left + width).toFloat(),
                                bottom = (top + height).toFloat(),
                                boxColor = if (name != "Unknown") Color.GREEN else Color.YELLOW
                            )
                        )
                    }

                    runOnUiThread {
                        overlayView.setResults(detections, bitmap.width, bitmap.height)
                        txtResult.text = if (topEmotion.isNotEmpty()) "$topName ($topEmotion)" else topName
                    }

                    announceFace(topName, topEmotion, bitmap.width)

                } catch (e: Throwable) {
                    Log.e("FaceRecognition", "DIAGNOSTIC: كراش أثناء المعالجة", e)
                    runOnUiThread { txtResult.text = "Error: ${e.javaClass.simpleName} - ${e.message}" }
                } finally {
                    isProcessing = false
                }
            }
            .addOnFailureListener {
                Log.e("FaceRecognition", "Face detection failed: ${it.message}")
                isProcessing = false
            }
    }

    /** بديل شرط النطق في face_mode(): اسم مختلف + كولداون + مش بيتكلم دلوقتي، ومع مسافة حقيقية لو متاحة */
    private fun announceFace(name: String, emotion: String, frameWidth: Int) {
        val announceKey = "$name|$emotion"
        val now = System.currentTimeMillis()
        if (announceKey != lastAnnouncedFace && now - lastFaceTime > faceCooldownMs) {
            var message = if (emotion.isNotEmpty()) "$name, looks $emotion" else name

            val sensorMm = latestSensorDistanceMm
            if (sensorMm != null && sensorMm in 1..4000) {
                message += ", ${DistanceUtils.formatDistance(sensorMm / 1000.0)} away"
            }

            ttsManager.speakNow(message)
            lastAnnouncedFace = announceKey
            lastFaceTime = now
        }
    }

    // ============================================================
    //  تغيير المود بالصوت - زرار تخفيض الصوت
    // ============================================================
    private val modeSwitchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull() ?: ""
            val target = VoiceModeSwitcher.matchMode(spokenText)
            if (target != null && target != FaceRecognitionActivity::class.java) {
                startActivity(Intent(this, target))
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            startListeningForModeSwitch()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startListeningForModeSwitch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a mode")
        }
        if (intent.resolveActivity(packageManager) != null) {
            modeSwitchLauncher.launch(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamReader.stop()
        distanceSensorReader.stop()
        processingExecutor.shutdown()
        ttsManager.shutdown()
        liveDetector.close()
        if (::knownFacesRepository.isInitialized) knownFacesRepository.close()
        if (::faceEmbedder.isInitialized) faceEmbedder.close()
        if (::emotionEngine.isInitialized) emotionEngine.close()
    }
}