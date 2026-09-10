package com.visionassist.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شاشة ولي الأمر: أول مرة بيدخل كود الربط بتاع الأعمى (pairCode)، وبعد كده
 * الشاشة بتفتح على طول على آخر موقع معروف للأعمى (بتجيبه مرة واحدة بس وقت ما الشاشة تفتح،
 * مش تتبع مستمر).
 */
class GuardianActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val prefs by lazy { getSharedPreferences("guardian_prefs", MODE_PRIVATE) }

    private lateinit var pairingSection: LinearLayout
    private lateinit var locationSection: LinearLayout
    private lateinit var etPairCode: EditText
    private lateinit var txtPairingStatus: TextView
    private lateinit var txtLocationInfo: TextView

    private var linkedBlindUid: String? = null
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian)

        pairingSection = findViewById(R.id.pairingSection)
        locationSection = findViewById(R.id.locationSection)
        etPairCode = findViewById(R.id.etPairCode)
        txtPairingStatus = findViewById(R.id.txtPairingStatus)
        txtLocationInfo = findViewById(R.id.txtLocationInfo)

        findViewById<Button>(R.id.btnLink).setOnClickListener {
            linkWithPairCode(etPairCode.text.toString().trim())
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            fetchLocation()
        }

        findViewById<Button>(R.id.btnViewPhotos).setOnClickListener {
            startActivity(Intent(this, PhotoReportActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenMaps).setOnClickListener {
            openInMaps()
        }

        findViewById<Button>(R.id.btnUnlink).setOnClickListener {
            unlink()
        }

        findViewById<Button>(R.id.btnSignOutGuardian).setOnClickListener {
            signOut()
        }

        findViewById<Button>(R.id.btnSignOutFromPairing).setOnClickListener {
            signOut()
        }

        linkedBlindUid = prefs.getString("linkedBlindUid", null)
        if (linkedBlindUid != null) {
            showLocationSection()
            fetchLocation()
        } else {
            showPairingSection()
        }
    }

    private fun showPairingSection() {
        pairingSection.visibility = android.view.View.VISIBLE
        locationSection.visibility = android.view.View.GONE
    }

    private fun showLocationSection() {
        pairingSection.visibility = android.view.View.GONE
        locationSection.visibility = android.view.View.VISIBLE
    }

    /** بديل ربط ولي الأمر بالأعمى - بندور على مستخدم role=blind وله نفس pairCode */
    private fun linkWithPairCode(code: String) {
        if (code.isEmpty()) {
            txtPairingStatus.text = "Please enter a pairing code"
            return
        }

        txtPairingStatus.text = "Searching..."

        db.collection("users")
            .whereEqualTo("pairCode", code)
            .whereEqualTo("role", "blind")
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    txtPairingStatus.text = "No blind user found with this code"
                    return@addOnSuccessListener
                }

                val doc = snapshot.documents[0]
                val uid = doc.id
                val name = doc.getString("name") ?: "Unknown"

                prefs.edit()
                    .putString("linkedBlindUid", uid)
                    .putString("linkedBlindName", name)
                    .apply()

                linkedBlindUid = uid
                Toast.makeText(this, "Linked to $name", Toast.LENGTH_SHORT).show()

                showLocationSection()
                fetchLocation()
            }
            .addOnFailureListener { e ->
                Log.e("GuardianActivity", "DIAGNOSTIC: linking failed", e)
                txtPairingStatus.text = "Error: ${e.message}"
            }
    }

    /** بديل عرض الموقع - بيجيب آخر موقع معروف مرة واحدة بس (مش تحديث مستمر) */
    private fun fetchLocation() {
        val uid = linkedBlindUid ?: return
        txtLocationInfo.text = "Loading location..."

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val lat = doc.getDouble("lat")
                val lng = doc.getDouble("lng")
                val updatedAt = doc.getLong("locationUpdatedAt")

                if (lat == null || lng == null) {
                    txtLocationInfo.text = "No location reported yet. Ask them to open the app."
                    return@addOnSuccessListener
                }

                lastLat = lat
                lastLng = lng

                val timeText = if (updatedAt != null) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    sdf.format(Date(updatedAt))
                } else "unknown time"

                txtLocationInfo.text = "Lat: %.6f\nLng: %.6f\nLast updated: %s".format(lat, lng, timeText)
                Log.d("GuardianActivity", "DIAGNOSTIC: location = $lat, $lng at $timeText")
            }
            .addOnFailureListener { e ->
                Log.e("GuardianActivity", "DIAGNOSTIC: fetch location failed", e)
                txtLocationInfo.text = "Error loading location: ${e.message}"
            }
    }

    private fun openInMaps() {
        val lat = lastLat
        val lng = lastLng
        if (lat == null || lng == null) {
            Toast.makeText(this, "No location available yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("https://maps.google.com/?q=$lat,$lng")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun unlink() {
        prefs.edit().clear().apply()
        linkedBlindUid = null
        etPairCode.setText("")
        txtPairingStatus.text = ""
        showPairingSection()
    }

    private fun signOut() {
        FirebaseAuth.getInstance().signOut()
        prefs.edit().clear().apply()

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}