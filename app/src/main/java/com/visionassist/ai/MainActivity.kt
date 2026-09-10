package com.visionassist.ai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // يفتح شاشة تسجيل الدخول مباشرة
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}