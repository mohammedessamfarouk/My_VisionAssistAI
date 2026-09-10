package com.visionassist.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * نفس منطق ObjectDetectionActivity بالظبط (YOLO + مشاعر + لقطات دورية)،
 * بس المصدر دلوقتي بث MJPEG من كاميرا ESP32-CAM (عن طريق Esp32CamStreamReader)
 * بدل كاميرا التليفون (CameraX).
 */
class ObjectDetectionExternalActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var txtResult: TextView

    private lateinit var ttsManager: TTSManager
    private lateinit var yoloEngine: YoloEngine
    private lateinit var emotionEngine: EmotionEngine

    private val streamReader = Esp32CamStreamReader()
    private val distanceSensorReader = DistanceSensorReader()
    @Volatile private var latestSensorDistanceMm: Int? = null
    private val distanceSensorIp = "192.168.1.17"

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )
    private var latestFaceEmotion = ""
    @Volatile private var isFaceProcessing = false
    @Volatile private var isProcessing = false
    private val processingExecutor = Executors.newSingleThreadExecutor()

    private var frameCounter = 0
    private val frameSkip = 2 // البث أصلاً أبطأ من كاميرا التليفون، فمش محتاجين تخطي فريمات كتير

    // عنوان الكاميرا الخارجية - غيّره هنا لو الـ IP اتغيّر
    private val esp32CamIp = "192.168.1.3"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_object_detection_external)

        imageView = findViewById(R.id.imageView)
        overlayView = findViewById(R.id.overlayView)
        txtResult = findViewById(R.id.txtResult)

        ttsManager = TTSManager(this)

        Thread {
            try {
                yoloEngine = YoloEngine(this)
                emotionEngine = EmotionEngine(this)
                runOnUiThread {
                    connectToCamera()
                    startDistanceSensor()
                }
            } catch (e: Throwable) {
                Log.e("ObjectDetectionExternal", "DIAGNOSTIC: فشل تحميل YoloEngine", e)
                runOnUiThread { txtResult.text = "Error: ${e.javaClass.simpleName} - ${e.message}" }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // نشغّل الكاميرا والحساس بس لو الموديلات خلصت تحميل بالفعل (أول مرة بيحصل من الـ callback فوق)
        if (::yoloEngine.isInitialized) {
            connectToCamera()
            startDistanceSensor()
        }
    }

    override fun onPause() {
        super.onPause()
        // نوقف الكاميرا والحساس تمامًا لما الشاشة متبقاش ظاهرة - عشان متفضلش تتكلم في الخلفية
        streamReader.stop()
        distanceSensorReader.stop()
    }

    private fun connectToCamera() {
        val url = "http://$esp32CamIp:81/stream"
        txtResult.text = "Connecting to $url ..."

        streamReader.start(
            streamUrl = url,
            onFrame = { bitmap ->
                // الفيديو نفسه بيتحدث كل فريم عشان يفضل سلس، مش مرتبط بسرعة الكشف
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
            onError = { e -> Log.e("ObjectDetectionExternal", "DIAGNOSTIC: distance sensor error", e) }
        )
    }

    private fun processFrame(bitmap: Bitmap) {
        frameCounter++
        if (frameCounter % frameSkip != 0 || !::yoloEngine.isInitialized) return

        // لو الكشف لسه شغال على فريم سابق، نتجاهل الفريم ده بدل ما نراكمه
        if (isProcessing) return
        isProcessing = true

        processingExecutor.execute {
            try {
                val detections = yoloEngine.detect(bitmap)

                PeriodicSnapshotManager.maybeCapture(bitmap)
                detectFaceEmotion(bitmap)

                runOnUiThread {
                    overlayView.setResults(detections, bitmap.width, bitmap.height)
                    txtResult.text = "${detections.size} objects detected"
                }

                announceDetections(detections, bitmap.width)

            } catch (e: Exception) {
                Log.e("ObjectDetectionExternal", "processFrame error", e)
            } finally {
                isProcessing = false
            }
        }
    }

    private fun detectFaceEmotion(bitmap: Bitmap) {
        if (isFaceProcessing || !::emotionEngine.isInitialized) return
        isFaceProcessing = true

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    try {
                        val box = faces[0].boundingBox
                        val left = max(0, box.left)
                        val top = max(0, box.top)
                        val width = min(bitmap.width - left, box.width())
                        val height = min(bitmap.height - top, box.height())

                        if (width > 0 && height > 0) {
                            val faceCrop = Bitmap.createBitmap(bitmap, left, top, width, height)
                            val (emotion, confidence) = emotionEngine.predictSmoothed(faceCrop, faceId = "objdet_ext_face")
                            latestFaceEmotion = emotion
                        }
                    } catch (e: Exception) {
                        Log.e("ObjectDetectionExternal", "face emotion crop error", e)
                    }
                } else {
                    latestFaceEmotion = ""
                }
                isFaceProcessing = false
            }
            .addOnFailureListener { isFaceProcessing = false }
    }

    private fun announceDetections(detections: List<Detection>, frameWidth: Int) {
        if (detections.isEmpty()) return

        val top = detections.take(3)
        val descriptions = top.map { det ->
            val direction = DistanceUtils.getDirection(det.centerX, frameWidth)
            val kind = if (det.label.lowercase() == "person") "person" else "default"
            val isCentered = det.centerX > frameWidth / 3f && det.centerX < 2 * frameWidth / 3f

            // لو الجسم في نص الشاشة (في مسار الحساس) وعندنا قراءة حديثة، نستخدم الرقم الحقيقي
            val sensorMm = latestSensorDistanceMm
            val distance = if (isCentered && sensorMm != null && sensorMm in 1..4000) {
                sensorMm / 1000.0
            } else {
                DistanceUtils.estimateDistanceM(det.height.toDouble(), kind = kind)
            }

            if (kind == "person" && latestFaceEmotion.isNotEmpty()) {
                "${det.label} $direction ${DistanceUtils.formatDistance(distance)}, looks $latestFaceEmotion"
            } else {
                "${det.label} $direction ${DistanceUtils.formatDistance(distance)}"
            }
        }

        ttsManager.speakIfDue(descriptions.joinToString(", "), cooldownMs = 8000)
    }

    // ============================================================
    //  زرار تخفيض الصوت = تغيير المود بالصوت | زرار تعلية الصوت = رجوع خطوة
    // ============================================================
    private val modeSwitchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull() ?: ""
            val target = VoiceModeSwitcher.matchMode(spokenText)
            if (target != null && target != ObjectDetectionExternalActivity::class.java) {
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
        if (::yoloEngine.isInitialized) yoloEngine.close()
        if (::emotionEngine.isInitialized) emotionEngine.close()
        faceDetector.close()
    }
}