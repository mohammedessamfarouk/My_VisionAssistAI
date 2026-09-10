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
import java.util.concurrent.Executors

/**
 * بديل money_mode() في الكود البايثون الأصلي.
 * نفس فكرة الـ buffer: لازم نفس الفئة تتكرر moneyBufferSize مرة متتالية قبل ما ننطق بيها.
 *
 * ============================================================
 * تحديث: المصدر بقى كاميرا ESP32-CAM الخارجية بدل كاميرا التليفون.
 * ============================================================
 */
class CurrencyActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var txtResult: TextView

    private lateinit var ttsManager: TTSManager
    private lateinit var moneyEngine: MoneyEngine

    private val streamReader = Esp32CamStreamReader()
    private val processingExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isProcessing = false

    // غيّر الـ IP ده لو اتغيّر عندك
    private val esp32CamIp = "192.168.1.3"

    // نفس منطق money_buffer / MONEY_BUFFER_SIZE بالظبط
    private val moneyBuffer = ArrayDeque<String>()
    private val moneyBufferSize = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency)

        imageView = findViewById(R.id.imageView)
        overlayView = findViewById(R.id.overlayView)
        txtResult = findViewById(R.id.txtResult)

        ttsManager = TTSManager(this)

        Executors.newSingleThreadExecutor().execute {
            try {
                moneyEngine = MoneyEngine(this)
                runOnUiThread { connectToCamera() }
            } catch (e: Throwable) {
                Log.e("CurrencyActivity", "DIAGNOSTIC: فشل تحميل MoneyEngine", e)
                runOnUiThread { txtResult.text = "Error: ${e.javaClass.simpleName} - ${e.message}" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::moneyEngine.isInitialized) connectToCamera()
    }

    override fun onPause() {
        super.onPause()
        streamReader.stop()
    }

    private fun connectToCamera() {
        val url = "http://$esp32CamIp:81/stream"
        txtResult.text = "Connecting to $url ..."

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

    private fun processFrame(bitmap: Bitmap) {
        if (isProcessing || !::moneyEngine.isInitialized) return
        isProcessing = true

        processingExecutor.execute {
            try {
                val detections = moneyEngine.detect(bitmap)

                runOnUiThread {
                    overlayView.setResults(detections, bitmap.width, bitmap.height)
                    txtResult.text = if (detections.isEmpty()) "No currency detected"
                    else "${detections.size} note(s) seen"
                }

                handleMoneyBuffer(detections)

            } catch (e: Exception) {
                Log.e("CurrencyActivity", "processFrame error", e)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * نفس منطق الكود الأصلي بالظبط:
     * for box in results: money_buffer.append(label)
     * if money_buffer.count(label) >= moneyBufferSize: speak + clear buffer
     */
    private fun handleMoneyBuffer(detections: List<Detection>) {
        for (det in detections) {
            moneyBuffer.addLast(det.label)
            if (moneyBuffer.size > moneyBufferSize) moneyBuffer.removeFirst()

            val matchCount = moneyBuffer.count { it == det.label }
            Log.d("CurrencyActivity", "DIAGNOSTIC: label='${det.label}' buffer=$moneyBuffer matchCount=$matchCount")

            if (matchCount >= moneyBufferSize) {
                Log.d("CurrencyActivity", "DIAGNOSTIC: هنطق دلوقتي بـ '${det.label}'")
                ttsManager.speakIfDue(det.label, cooldownMs = 5000)
                moneyBuffer.clear()
                break
            }
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
            if (target != null && target != CurrencyActivity::class.java) {
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
        processingExecutor.shutdown()
        ttsManager.shutdown()
        if (::moneyEngine.isInitialized) moneyEngine.close()
    }
}