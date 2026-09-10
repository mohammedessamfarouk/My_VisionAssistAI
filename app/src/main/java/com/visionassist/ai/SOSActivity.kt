package com.visionassist.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * شاشة الطوارئ: أول ما تفتح، بتبعت SMS فيه موقع الشخص، وتتصل تلقائيًا على رقم الطوارئ.
 */
class SOSActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var ttsManager: TTSManager
    private val cancellationTokenSource = CancellationTokenSource()

    private val emergencyNumber = "01064466566"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sosactivity)

        txtStatus = findViewById(R.id.txtStatus)
        ttsManager = TTSManager(this)

        ttsManager.speakNow("Emergency mode activated")
        checkPermissionsAndProceed()
    }

    private fun checkPermissionsAndProceed() {
        val smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
        val callGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (smsGranted && callGranted && locationGranted) {
            sendEmergencySms()
        } else {
            requestPermissions.launch(
                arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val smsOk = results[Manifest.permission.SEND_SMS] == true
            val callOk = results[Manifest.permission.CALL_PHONE] == true

            if (smsOk) {
                sendEmergencySms()
            } else {
                txtStatus.text = "SMS permission denied"
            }

            if (callOk) {
                makeEmergencyCall()
            } else {
                txtStatus.text = "Call permission denied"
            }
        }

    @Suppress("MissingPermission")
    private fun sendEmergencySms() {
        txtStatus.text = "Getting your location..."

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                val message = if (location != null) {
                    "EMERGENCY! I need help. My location: https://maps.google.com/?q=${location.latitude},${location.longitude}"
                } else {
                    "EMERGENCY! I need help. Location unavailable."
                }
                sendSms(message)
            }
            .addOnFailureListener { e ->
                Log.e("SOSActivity", "location error", e)
                sendSms("EMERGENCY! I need help. Location unavailable.")
            }

        makeEmergencyCall()
    }

    private fun sendSms(message: String) {
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(emergencyNumber, null, parts, null, null)

            Log.d("SOSActivity", "DIAGNOSTIC: SMS sent to $emergencyNumber: $message")
            runOnUiThread {
                txtStatus.text = "SMS sent to $emergencyNumber"
            }
            ttsManager.speakNow("Emergency message sent")
        } catch (e: Exception) {
            Log.e("SOSActivity", "DIAGNOSTIC: SMS failed", e)
            runOnUiThread { txtStatus.text = "Failed to send SMS: ${e.message}" }
        }
    }

    private fun makeEmergencyCall() {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$emergencyNumber")
            }
            startActivity(intent)
            Log.d("SOSActivity", "DIAGNOSTIC: calling $emergencyNumber")
        } catch (e: Exception) {
            Log.e("SOSActivity", "DIAGNOSTIC: call failed", e)
            runOnUiThread { txtStatus.text = "Failed to call: ${e.message}" }
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
            Log.d("SOSActivity", "DIAGNOSTIC: mode switch heard = $spokenText")
            val target = VoiceModeSwitcher.matchMode(spokenText)
            if (target != null && target != SOSActivity::class.java) {
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
        cancellationTokenSource.cancel()
        ttsManager.shutdown()
    }
}