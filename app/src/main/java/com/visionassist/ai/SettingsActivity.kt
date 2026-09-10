package com.visionassist.ai

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * بديل: شاشة تعرض وتنطق كود الربط (pairCode)، وكمان دلوقتي فيها إعدادات شبكة
 * الهاردوير (IP الكاميرا والحساس) - نتغير من هنا مباشرة من غير أي Rebuild.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var ttsManager: TTSManager
    private lateinit var txtPairCode: TextView
    private lateinit var etCameraIp: EditText
    private lateinit var etSensorIp: EditText
    private lateinit var txtSaveStatus: TextView
    private var pairCode: String = ""

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        txtPairCode = findViewById(R.id.txtPairCode)
        etCameraIp = findViewById(R.id.etCameraIp)
        etSensorIp = findViewById(R.id.etSensorIp)
        txtSaveStatus = findViewById(R.id.txtSaveStatus)
        ttsManager = TTSManager(this)

        // نعرض الـ IPs المحفوظة حاليًا
        etCameraIp.setText(NetworkConfig.getCameraIp(this))
        etSensorIp.setText(NetworkConfig.getSensorIp(this))

        findViewById<Button>(R.id.btnRepeatCode).setOnClickListener {
            speakPairCode()
        }

        findViewById<Button>(R.id.btnSaveIps).setOnClickListener {
            saveNetworkSettings()
        }

        findViewById<Button>(R.id.btnSignOut).setOnClickListener {
            signOut()
        }

        loadPairCode()
    }

    private fun saveNetworkSettings() {
        val cameraIp = etCameraIp.text.toString().trim()
        val sensorIp = etSensorIp.text.toString().trim()

        if (cameraIp.isEmpty() || sensorIp.isEmpty()) {
            txtSaveStatus.setTextColor(0xFFB00020.toInt())
            txtSaveStatus.text = "Please fill in both IPs"
            return
        }

        NetworkConfig.setCameraIp(this, cameraIp)
        NetworkConfig.setSensorIp(this, sensorIp)

        txtSaveStatus.setTextColor(0xFF2E7D32.toInt())
        txtSaveStatus.text = "Saved! New screens will use these IPs"
        ttsManager.speakNow("Network settings saved")
    }

    private fun signOut() {
        auth.signOut()
        getSharedPreferences("guardian_prefs", MODE_PRIVATE).edit().clear().apply()

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun loadPairCode() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            txtPairCode.text = "Not logged in"
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                pairCode = doc.getString("pairCode") ?: ""
                if (pairCode.isNotEmpty()) {
                    txtPairCode.text = pairCode
                    speakPairCode()
                } else {
                    txtPairCode.text = "Not available"
                }
            }
            .addOnFailureListener { e ->
                Log.e("SettingsActivity", "DIAGNOSTIC: failed to load pairCode", e)
                txtPairCode.text = "Error loading code"
            }
    }

    private fun speakPairCode() {
        if (pairCode.isEmpty()) return
        val spacedOut = pairCode.toCharArray().joinToString(" ")
        ttsManager.speakNow("Your pairing code is $spacedOut")
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}