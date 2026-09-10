package com.visionassist.ai

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ChooseUserActivity : AppCompatActivity() {

    private lateinit var ttsManager: TTSManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_choose_user)

        ttsManager = TTSManager(this)

        val blind = findViewById<Button>(R.id.btnBlind)
        val guardian = findViewById<Button>(R.id.btnGuardian)

        blind.setOnClickListener {
            ttsManager.speakNow("Blind mode selected")
            startActivity(Intent(this, BlindActivity::class.java))
        }

        guardian.setOnClickListener {
            ttsManager.speakNow("Guardian mode selected")
            startActivity(Intent(this, GuardianActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}