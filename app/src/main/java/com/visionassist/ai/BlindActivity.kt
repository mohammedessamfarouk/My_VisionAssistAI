package com.visionassist.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale

/**
 * بديل MODE_KEYWORDS + _background_listener() في الكود البايثون الأصلي.
 * الشاشة دلوقتي بتسمع أوامر صوتية تلقائيًا (من غير ما تحتاج تدوس زرار) وتفتح
 * الشاشة المناسبة على طول. الأزرار لسه موجودة كبديل يدوي احتياطي.
 */
class BlindActivity : AppCompatActivity() {

    private lateinit var ttsManager: TTSManager
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val cancellationTokenSource = CancellationTokenSource()
    private val handler = Handler(Looper.getMainLooper())

    private var hasSpokenWelcome = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blind)

        ttsManager = TTSManager(this)

        // الأزرار بديل يدوي احتياطي - لسه شغالة زي ما هي
        findViewById<Button>(R.id.btnObjects).setOnClickListener {
            startActivity(Intent(this, ObjectDetectionExternalActivity::class.java))
        }
        findViewById<Button>(R.id.btnRead).setOnClickListener {
            startActivity(Intent(this, OCRActivity::class.java))
        }
        findViewById<Button>(R.id.btnMoney).setOnClickListener {
            startActivity(Intent(this, CurrencyActivity::class.java))
        }
        findViewById<Button>(R.id.btnNavigation).setOnClickListener {
            startActivity(Intent(this, NavigationActivity::class.java))
        }
        findViewById<Button>(R.id.btnFace).setOnClickListener {
            startActivity(Intent(this, FaceRecognitionActivity::class.java))
        }
        findViewById<Button>(R.id.btnSOS).setOnClickListener {
            startActivity(Intent(this, SOSActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

    }

    override fun onResume() {
        super.onResume()
        updateLocationInFirestore()

        if (!hasSpokenWelcome) {
            hasSpokenWelcome = true
            ttsManager.speakNow("Blind mode activated. Say a command: objects, reading, money, navigation, face, S O S, or settings")
        }

        // بديل الاستماع المستمر: كل ما نرجع للشاشة دي (سواء أول مرة أو بعد ما نرجع من شاشة تانية)
        // نبدأ نسمع أمر جديد تلقائيًا من غير ما يحتاج يدوس أي حاجة
        handler.postDelayed({ startListeningForCommand() }, 1500)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    // ================= الاستماع للأوامر الصوتية =================
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()?.lowercase() ?: ""
            Log.d("BlindActivity", "DIAGNOSTIC: heard command = $spokenText")
            handleCommand(spokenText)
        } else {
            Log.w("BlindActivity", "DIAGNOSTIC: speech cancelled/failed, resultCode=${result.resultCode}")
            // نعيد المحاولة تلقائيًا بعد شوية
            handler.postDelayed({ startListeningForCommand() }, 1500)
        }
    }

    private fun startListeningForCommand() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra("android.speech.extra.EXTRA_LANGUAGE", "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command")
        }

        if (intent.resolveActivity(packageManager) == null) {
            Log.w("BlindActivity", "DIAGNOSTIC: no voice input app found")
            return
        }

        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("BlindActivity", "DIAGNOSTIC: failed to launch speech intent", e)
        }
    }

    /** بديل process_voice_queue() في الكود الأصلي - بيدور على أول كلمة مطابقة ويفتح الشاشة بتاعتها */
    private fun handleCommand(spokenText: String) {
        if (spokenText.isBlank()) {
            ttsManager.speakNow("Didn't catch that")
            handler.postDelayed({ startListeningForCommand() }, 1500)
            return
        }

        val target = VoiceModeSwitcher.matchMode(spokenText)
        if (target != null) {
            startActivity(Intent(this, target))
            return
        }

        ttsManager.speakNow("Command not recognized, try again")
        handler.postDelayed({ startListeningForCommand() }, 1500)
    }

    // ================= تحديث الموقع (زي ما كان) =================
    private fun updateLocationInFirestore() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        val uid = auth.currentUser?.uid ?: return

        @Suppress("MissingPermission")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val data = mapOf(
                        "lat" to location.latitude,
                        "lng" to location.longitude,
                        "locationUpdatedAt" to System.currentTimeMillis()
                    )
                    db.collection("users").document(uid)
                        .set(data, SetOptions.merge())
                        .addOnSuccessListener {
                            Log.d("BlindActivity", "DIAGNOSTIC: location updated: ${location.latitude}, ${location.longitude}")
                        }
                        .addOnFailureListener { e ->
                            Log.e("BlindActivity", "DIAGNOSTIC: Firestore location update failed", e)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("BlindActivity", "DIAGNOSTIC: location fetch failed", e)
            }
    }

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) updateLocationInFirestore()
        }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cancellationTokenSource.cancel()
        ttsManager.shutdown()
    }
}