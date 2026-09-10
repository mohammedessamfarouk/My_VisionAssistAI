package com.visionassist.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * الكاميرا الخارجية (ESP32-CAM) + حساس المسافة الحقيقي + GPS بالعربي + تحكم صوتي كامل.
 */
class NavigationActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var txtResult: TextView
    private lateinit var dangerBar: View

    private lateinit var ttsManager: TTSManager
    private lateinit var yoloEngine: YoloEngine
    private lateinit var emotionEngine: EmotionEngine

    private val streamReader = Esp32CamStreamReader()
    private val distanceSensorReader = DistanceSensorReader()
    private val processingExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isProcessing = false
    @Volatile private var latestSensorDistanceMm: Int? = null

    // غيّر الـ IPs دول لو اتغيروا على شبكتك
    private val esp32CamIp = "192.168.1.3"
    private val distanceSensorIp = "192.168.1.17"

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )
    private var latestFaceEmotion = ""
    @Volatile private var isFaceProcessing = false
    @Volatile private var isTakingDestinationInput = false

    private val areaRatioThreshold = 0.02f

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double? = null
    private var currentLon: Double? = null
    private val cancellationTokenSource = CancellationTokenSource()

    private var bearingBaselineLocation: Location? = null
    private var currentMovementBearing: Float? = null
    private val minMovementForBearingMeters = 15f

    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            currentLat = location.latitude
            currentLon = location.longitude
            updateMovementBearing(location)
            if (isNavigating) updateNavigationGuidance()
        }
    }

    private var destinationLat: Double? = null
    private var destinationLon: Double? = null
    private var destinationName: String = ""
    private var isNavigating = false
    private var lastGuidanceTime = 0L
    private val guidanceCooldownMs = 8000L
    private val arrivalThresholdMeters = 15f

    private data class Obstacle(
        val distance: Double, val msg: String, val danger: String, val isPerson: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        imageView = findViewById(R.id.imageView)
        overlayView = findViewById(R.id.overlayView)
        txtResult = findViewById(R.id.txtResult)
        dangerBar = findViewById(R.id.dangerBar)

        ttsManager = TTSManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        findViewById<Button>(R.id.btnSetDestination).setOnClickListener {
            startDestinationVoiceInput()
        }

        Thread {
            try {
                yoloEngine = YoloEngine(this)
                emotionEngine = EmotionEngine(this)
                runOnUiThread { checkPermissions() }
            } catch (e: Throwable) {
                Log.e("NavigationActivity", "DIAGNOSTIC: فشل تحميل YoloEngine", e)
                runOnUiThread { txtResult.text = "Error: ${e.javaClass.simpleName} - ${e.message}" }
            }
        }.start()
    }

    private fun checkPermissions() {
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (locationGranted && audioGranted) {
            startExternalCamera(); startDistanceSensor(); startLocationTracking()
        } else {
            requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO))
        }
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val locationOk = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
            startExternalCamera(); startDistanceSensor()
            if (locationOk) startLocationTracking()
        }

    override fun onResume() {
        super.onResume()
        if (::yoloEngine.isInitialized) { startExternalCamera(); startDistanceSensor() }
    }

    override fun onPause() {
        super.onPause()
        streamReader.stop()
        distanceSensorReader.stop()
    }

    private fun startExternalCamera() {
        val url = "http://$esp32CamIp:81/stream"
        txtResult.text = "Connecting to camera..."
        streamReader.start(
            streamUrl = url,
            onFrame = { bitmap ->
                runOnUiThread { imageView.setImageBitmap(bitmap) }
                processFrame(bitmap)
            },
            onError = { e -> runOnUiThread { txtResult.text = "Camera error: ${e.message}" } }
        )
    }

    private fun startDistanceSensor() {
        distanceSensorReader.start(
            ip = distanceSensorIp,
            onDistance = { mm -> latestSensorDistanceMm = mm },
            onError = { e -> Log.e("NavigationActivity", "sensor error", e) }
        )
    }

    private fun processFrame(bitmap: Bitmap) {
        if (isProcessing || !::yoloEngine.isInitialized) return
        isProcessing = true
        processingExecutor.execute {
            try {
                val allDetections = yoloEngine.detect(bitmap)
                PeriodicSnapshotManager.maybeCapture(bitmap)

                val totalArea = (bitmap.width * bitmap.height).toFloat()
                val filtered = allDetections.filter { (it.width * it.height) / totalArea > areaRatioThreshold }
                val (coloredDetections, obstacles) = buildObstacles(filtered, bitmap.width)

                runOnUiThread {
                    overlayView.setResults(coloredDetections, bitmap.width, bitmap.height)
                    txtResult.text = "NAVIGATION | ${obstacles.size} obstacles"
                    updateDangerBar(obstacles)
                }

                detectFaceEmotion(bitmap)
                announceClosest(obstacles)
            } catch (e: Exception) {
                Log.e("NavigationActivity", "processFrame error", e)
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
                        val left = max(0, box.left); val top = max(0, box.top)
                        val width = min(bitmap.width - left, box.width())
                        val height = min(bitmap.height - top, box.height())
                        if (width > 0 && height > 0) {
                            val faceCrop = Bitmap.createBitmap(bitmap, left, top, width, height)
                            val (emotion, _) = emotionEngine.predictSmoothed(faceCrop, faceId = "nav_face")
                            latestFaceEmotion = emotion
                        }
                    } catch (e: Exception) { Log.e("NavigationActivity", "emotion error", e) }
                } else latestFaceEmotion = ""
                isFaceProcessing = false
            }
            .addOnFailureListener { isFaceProcessing = false }
    }

    private fun buildObstacles(detections: List<Detection>, frameWidth: Int): Pair<List<Detection>, List<Obstacle>> {
        val obstacles = mutableListOf<Obstacle>()
        val colored = mutableListOf<Detection>()
        for (det in detections) {
            val kind = if (det.label.lowercase() == "person") "person" else "default"
            val direction = DistanceUtils.getDirection(det.centerX, frameWidth)
            val isCentered = det.centerX > frameWidth / 3f && det.centerX < 2 * frameWidth / 3f

            val sensorMm = latestSensorDistanceMm
            val distance = if (isCentered && sensorMm != null && sensorMm in 1..4000) {
                sensorMm / 1000.0
            } else {
                DistanceUtils.estimateDistanceNav(det.height.toDouble(), kind = kind)
            }

            val (danger, color) = when {
                distance < 1.0 && isCentered -> "DANGER" to Color.RED
                distance < 2.0 -> "warning" to Color.rgb(255, 140, 0)
                else -> "notice" to Color.YELLOW
            }
            val msg = "$danger, ${det.label} $direction, ${DistanceUtils.formatDistance(distance)}"
            obstacles.add(Obstacle(distance, msg, danger, isPerson = kind == "person"))
            colored.add(det.copy(boxColor = color))
        }
        return colored to obstacles.sortedBy { it.distance }
    }

    private fun updateDangerBar(obstacles: List<Obstacle>) {
        val screenWidth = dangerBar.rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        if (obstacles.isEmpty()) {
            dangerBar.layoutParams.width = screenWidth
            dangerBar.setBackgroundColor(Color.rgb(0, 200, 0))
            dangerBar.requestLayout()
            return
        }
        val closest = obstacles.first()
        val barRatio = max(0.0, min(1.0, 1.0 - (closest.distance / 5.0)))
        val barColor = when {
            closest.distance < 1.0 -> Color.RED
            closest.distance < 2.0 -> Color.rgb(255, 140, 0)
            else -> Color.rgb(0, 200, 0)
        }
        dangerBar.layoutParams.width = (barRatio * screenWidth).toInt().coerceAtLeast(1)
        dangerBar.setBackgroundColor(barColor)
        dangerBar.requestLayout()
    }

    private fun announceClosest(obstacles: List<Obstacle>) {
        if (isTakingDestinationInput) return
        if (obstacles.isEmpty()) {
            val sensorMm = latestSensorDistanceMm
            if (sensorMm != null && sensorMm in 1..1000) {
                ttsManager.speakIfDue("warning, something close ahead, ${DistanceUtils.formatDistance(sensorMm / 1000.0)}", cooldownMs = 3000)
            } else {
                ttsManager.speakIfDue("Path is clear", cooldownMs = 10000)
            }
        } else {
            val closest = obstacles.first()
            val message = if (closest.isPerson && latestFaceEmotion.isNotEmpty()) "${closest.msg}, looks $latestFaceEmotion" else closest.msg
            ttsManager.speakIfDue(message, cooldownMs = 3000)
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isTakingDestinationInput = false
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()
            if (!spokenText.isNullOrBlank()) geocodeDestination(spokenText)
            else ttsManager.speakNow("معرفتش أسمعك، جرب تاني")
        }
    }

    private fun startDestinationVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "قول اسم المكان")
        }
        if (intent.resolveActivity(packageManager) == null) {
            ttsManager.speakNow("مفيش تطبيق تعرف على صوت على الجهاز ده"); return
        }
        isTakingDestinationInput = true
        try { speechLauncher.launch(intent) } catch (e: Exception) {
            ttsManager.speakNow("مقدرتش أفتح إدخال الصوت"); isTakingDestinationInput = false
        }
    }

    private fun geocodeDestination(placeName: String) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocationName(placeName, 1)
                if (results.isNullOrEmpty()) {
                    runOnUiThread { ttsManager.speakNow("معرفتش ألاقي $placeName") }
                    return@Thread
                }
                val place = results[0]
                destinationLat = place.latitude; destinationLon = place.longitude
                destinationName = placeName; isNavigating = true; lastGuidanceTime = 0L
                bearingBaselineLocation = null; currentMovementBearing = null

                runOnUiThread {
                    ttsManager.speakNow("تمام، الوجهة بقت $placeName. امشي عشان أقدر أوجهك")
                    if (currentLat != null && currentLon != null) updateNavigationGuidance()
                    else fetchFreshLocationThenGuide()
                }
            } catch (e: Exception) {
                runOnUiThread { ttsManager.speakNow("حصل خطأ وأنا بدور على المكان ده") }
            }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun fetchFreshLocationThenGuide() {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) { currentLat = location.latitude; currentLon = location.longitude; updateNavigationGuidance() }
            }
    }

    private fun updateMovementBearing(location: Location) {
        if (location.hasBearing() && location.hasSpeed() && location.speed > 0.5f) {
            currentMovementBearing = location.bearing; bearingBaselineLocation = location; return
        }
        val anchor = bearingBaselineLocation
        if (anchor == null) { bearingBaselineLocation = location; return }
        val results = FloatArray(3)
        Location.distanceBetween(anchor.latitude, anchor.longitude, location.latitude, location.longitude, results)
        if (results[0] >= minMovementForBearingMeters) { currentMovementBearing = results[1]; bearingBaselineLocation = location }
    }

    private fun updateNavigationGuidance() {
        if (!isNavigating) return
        val lat1 = currentLat; val lon1 = currentLon; val lat2 = destinationLat; val lon2 = destinationLon
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return

        val results = FloatArray(3)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        val distanceMeters = results[0]
        val targetBearing = (results[1] + 360) % 360

        if (distanceMeters < arrivalThresholdMeters) {
            ttsManager.speakNow("وصلت لـ $destinationName"); isNavigating = false; return
        }
        val now = System.currentTimeMillis()
        if (now - lastGuidanceTime < guidanceCooldownMs) return
        if (ttsManager.isBusy()) return
        lastGuidanceTime = now

        val distanceText = if (distanceMeters < 1000) "${distanceMeters.toInt()} متر" else "%.1f كيلومتر".format(distanceMeters / 1000)
        val targetDirectionName = bearingToCompassDirection(targetBearing)
        var message = "$destinationName ناحية $targetDirectionName، على بعد $distanceText"
        val movementBearing = currentMovementBearing
        message += if (movementBearing != null) "، وإنت دلوقتي ماشي ناحية ${bearingToCompassDirection(movementBearing)}"
        else "، امشي شوية عشان أقدر أقولك ماشي صح ولا لأ"
        ttsManager.speakNow(message)
    }

    private fun bearingToCompassDirection(angle: Float): String {
        val n = (angle + 360) % 360
        return when {
            n < 22.5 || n >= 337.5 -> "الشمال"; n < 67.5 -> "الشمال الشرقي"; n < 112.5 -> "الشرق"
            n < 157.5 -> "الجنوب الشرقي"; n < 202.5 -> "الجنوب"; n < 247.5 -> "الجنوب الغربي"
            n < 292.5 -> "الغرب"; else -> "الشمال الغربي"
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationTracking() {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location -> if (location != null) { currentLat = location.latitude; currentLon = location.longitude } }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private val modeSwitchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val target = VoiceModeSwitcher.matchMode(matches?.firstOrNull() ?: "")
            if (target != null && target != NavigationActivity::class.java) { startActivity(Intent(this, target)); finish() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { startListeningForModeSwitch(); return true }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { startDestinationVoiceInput(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun startListeningForModeSwitch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a mode")
        }
        if (intent.resolveActivity(packageManager) != null) modeSwitchLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        streamReader.stop(); distanceSensorReader.stop(); processingExecutor.shutdown()
        ttsManager.shutdown(); cancellationTokenSource.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (::yoloEngine.isInitialized) yoloEngine.close()
        if (::emotionEngine.isInitialized) emotionEngine.close()
        faceDetector.close()
    }
}