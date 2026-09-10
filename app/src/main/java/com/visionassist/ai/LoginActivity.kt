package com.visionassist.ai

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var ttsManager: TTSManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        ttsManager = TTSManager(this)

        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val loginBtn = findViewById<Button>(R.id.loginBtn)
        val createAccount = findViewById<TextView>(R.id.createAccount)

        // إذا كان المستخدم مسجل دخول بالفعل، نوجهه للشاشة المناسبة
        if (auth.currentUser != null) {
            checkUserRoleAndNavigate(auth.currentUser!!.uid)
        }

        createAccount.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        loginBtn.setOnClickListener {

            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString().trim()

            if (emailText.isEmpty() || passwordText.isEmpty()) {
                Toast.makeText(
                    this,
                    "Please enter email and password",
                    Toast.LENGTH_SHORT
                ).show()
                ttsManager.speakNow("Please enter email and password")
                return@setOnClickListener
            }

            ttsManager.speakNow("Logging in")

            auth.signInWithEmailAndPassword(emailText, passwordText)
                .addOnCompleteListener { task ->

                    if (task.isSuccessful) {

                        val user = auth.currentUser

                        if (user == null) {
                            Toast.makeText(
                                this,
                                "Login failed",
                                Toast.LENGTH_SHORT
                            ).show()
                            ttsManager.speakNow("Login failed")
                            return@addOnCompleteListener
                        }

                        checkUserRoleAndNavigate(user.uid)

                    } else {

                        val errorMessage = task.exception?.localizedMessage ?: "Login failed"
                        Toast.makeText(
                            this,
                            errorMessage,
                            Toast.LENGTH_LONG
                        ).show()
                        ttsManager.speakNow("Login failed: $errorMessage")
                    }

                }

        }
    }

    private fun checkUserRoleAndNavigate(uid: String) {
        db.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->

                if (document.exists()) {

                    val role = document.getString("role")

                    when (role) {

                        "blind" -> {
                            ttsManager.speakNow("Welcome blind user")
                            startActivity(
                                Intent(
                                    this,
                                    BlindActivity::class.java
                                )
                            )
                        }

                        "guardian" -> {
                            ttsManager.speakNow("Welcome guardian")
                            startActivity(
                                Intent(
                                    this,
                                    GuardianActivity::class.java
                                )
                            )
                        }

                        else -> {
                            Toast.makeText(
                                this,
                                "Invalid user role",
                                Toast.LENGTH_LONG
                            ).show()
                            ttsManager.speakNow("Invalid user role")
                            auth.signOut()
                        }
                    }

                    finish()

                } else {

                    Toast.makeText(
                        this,
                        "User data not found",
                        Toast.LENGTH_LONG
                    ).show()
                    ttsManager.speakNow("User data not found")
                    auth.signOut()

                }

            }
            .addOnFailureListener {

                Toast.makeText(
                    this,
                    it.localizedMessage,
                    Toast.LENGTH_LONG
                ).show()
                ttsManager.speakNow("Error loading user data")

            }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}