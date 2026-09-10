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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class OCRActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OCRActivity"

        private const val ESP32_CAM_IP = "192.168.1.3"

        private const val STREAM_URL =
            "http://$ESP32_CAM_IP:81/stream"
    }

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var txtDetectedText: TextView

    private lateinit var ttsManager: TTSManager

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private val streamReader =
        Esp32CamStreamReader()

    private val ocrExecutor =
        Executors.newSingleThreadExecutor()

    @Volatile
    private var isRecognizing = false

    @Volatile
    private var activityActive = false

    private var pendingText = ""
    private var pendingCount = 0
    private var lastSpokenText = ""
    private val stabilityThreshold = 3

    private val modeSwitchLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {

                val matches =
                    result.data?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )

                val spokenText =
                    matches?.firstOrNull() ?: ""

                Log.d(TAG, "Voice command = $spokenText")

                val target =
                    VoiceModeSwitcher.matchMode(spokenText)

                if (target != null && target != OCRActivity::class.java) {
                    startActivity(Intent(this, target))
                    finish()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocractivity)

        imageView = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)
        txtDetectedText = findViewById(R.id.txtDetectedText)

        ttsManager = TTSManager(this)

        statusText.text = "Connecting to camera..."
        txtDetectedText.text = ""

        Log.d(TAG, "OCRActivity started")
        Log.d(TAG, "ESP32 = $ESP32_CAM_IP")
        Log.d(TAG, "STREAM = $STREAM_URL")
    }

    override fun onResume() {
        super.onResume()
        activityActive = true
        connectToCamera()
    }

    override fun onPause() {
        activityActive = false
        streamReader.stop()
        super.onPause()
    }

    private fun connectToCamera() {
        if (!activityActive) return

        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                statusText.text = "Connecting to camera..."
                txtDetectedText.text = ""
            }
        }

        streamReader.start(
            streamUrl = STREAM_URL,

            onFrame = { bitmap ->

                if (!activityActive) return@start
                if (isFinishing || isDestroyed) return@start

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        imageView.setImageBitmap(bitmap)
                        statusText.text = "READING ENGLISH"
                    }
                }

                if (!isRecognizing) {
                    try {
                        ocrExecutor.execute {
                            if (activityActive && !isFinishing && !isDestroyed && !isRecognizing) {
                                processFrame(bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "OCR task error", e)
                    }
                }
            },

            onError = { e ->
                Log.e(TAG, "ESP32 stream error", e)
                if (activityActive && !isFinishing && !isDestroyed) {
                    runOnUiThread { statusText.text = "Camera error" }
                }
            }
        )
    }

    private fun processFrame(bitmap: Bitmap) {
        if (!activityActive) return
        if (isRecognizing) return
        isRecognizing = true

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (!activityActive) return@addOnSuccessListener

                    val detectedText = buildCompleteText(visionText)
                    Log.d(TAG, "COMPLETE OCR = [$detectedText]")

                    if (detectedText.isNotBlank()) {
                        handleRecognizedText(detectedText)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR failed", e)
                }
                .addOnCompleteListener {
                    isRecognizing = false
                }

        } catch (e: Exception) {
            isRecognizing = false
            Log.e(TAG, "OCR exception", e)
        }
    }

    private fun buildCompleteText(
        visionText: com.google.mlkit.vision.text.Text
    ): String {
        val result = StringBuilder()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                if (lineText.isNotEmpty()) {
                    if (result.isNotEmpty()) result.append(" ")
                    result.append(lineText)
                }
            }
        }

        return cleanOCRText(result.toString())
    }

    private fun cleanOCRText(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun handleRecognizedText(rawText: String) {
        if (!activityActive) return

        val text = cleanOCRText(rawText)
        if (text.isBlank()) return

        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                txtDetectedText.text =
                    if (text.length > 100) text.take(100) + "…" else text
            }
        }

        val normalized = normalize(text)
        val normalizedPending = normalize(pendingText)

        val sameText = normalizedPending.isNotEmpty() &&
                (normalized == normalizedPending ||
                        normalized.contains(normalizedPending) ||
                        normalizedPending.contains(normalized))

        if (sameText) {
            pendingCount++
            if (text.length > pendingText.length) pendingText = text
        } else {
            pendingText = text
            pendingCount = 1
        }

        Log.d(TAG, "Pending = [$pendingText] count=$pendingCount")

        if (pendingCount >= stabilityThreshold) {
            val finalText = cleanOCRText(pendingText)
            val normalizedFinal = normalize(finalText)

            if (normalizedFinal.isNotEmpty() && normalizedFinal != lastSpokenText) {
                lastSpokenText = normalizedFinal
                Log.d(TAG, "SPEAK COMPLETE TEXT = [$finalText]")

                try {
                    ttsManager.speakIfDue(finalText, cooldownMs = 6000)
                } catch (e: Exception) {
                    Log.e(TAG, "TTS error", e)
                }
            }
            pendingCount = 0
        }
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase().replace(Regex("\\s+"), " ")
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
        } else {
            Log.e(TAG, "Speech recognizer unavailable")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "OCRActivity destroyed")
        activityActive = false

        try { streamReader.stop() } catch (e: Exception) { Log.e(TAG, "Stream stop error", e) }
        try { ocrExecutor.shutdownNow() } catch (e: Exception) { Log.e(TAG, "Executor shutdown error", e) }
        try { ttsManager.shutdown() } catch (e: Exception) { Log.e(TAG, "TTS shutdown error", e) }
        try { recognizer.close() } catch (e: Exception) { Log.e(TAG, "Recognizer close error", e) }

        super.onDestroy()
    }
}

