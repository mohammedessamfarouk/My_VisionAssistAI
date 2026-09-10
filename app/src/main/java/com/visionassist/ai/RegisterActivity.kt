package com.visionassist.ai

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var ttsManager: TTSManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        ttsManager = TTSManager(this)

        val name = findViewById<EditText>(R.id.name)
        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val confirmPassword = findViewById<EditText>(R.id.confirmPassword)

        val rbBlind = findViewById<RadioButton>(R.id.rbBlind)
        val rbGuardian = findViewById<RadioButton>(R.id.rbGuardian)

        val registerBtn = findViewById<Button>(R.id.registerBtn)
        val loginText = findViewById<TextView>(R.id.loginText)

        loginText.setOnClickListener {
            finish()
        }

        registerBtn.setOnClickListener {

            val fullName = name.text.toString().trim()
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString().trim()
            val confirmText = confirmPassword.text.toString().trim()

            if (fullName.isEmpty() ||
                emailText.isEmpty() ||
                passwordText.isEmpty() ||
                confirmText.isEmpty()
            ) {
                Toast.makeText(
                    this,
                    "Please fill all fields",
                    Toast.LENGTH_SHORT
                ).show()
                ttsManager.speakNow("Please fill all fields")
                return@setOnClickListener
            }

            if (passwordText != confirmText) {
                Toast.makeText(
                    this,
                    "Passwords do not match",
                    Toast.LENGTH_SHORT
                ).show()
                ttsManager.speakNow("Passwords do not match")
                return@setOnClickListener
            }

            if (!rbBlind.isChecked && !rbGuardian.isChecked) {
                Toast.makeText(
                    this,
                    "Select account type",
                    Toast.LENGTH_SHORT
                ).show()
                ttsManager.speakNow("Select account type")
                return@setOnClickListener
            }

            val role =
                if (rbBlind.isChecked) "blind"
                else "guardian"

            ttsManager.speakNow("Creating account")

            auth.createUserWithEmailAndPassword(emailText, passwordText)
                .addOnCompleteListener { task ->

                    if (task.isSuccessful) {

                        val uid = auth.currentUser!!.uid

                        val pairCode =
                            UUID.randomUUID()
                                .toString()
                                .substring(0, 6)
                                .uppercase()

                        val user = hashMapOf(
                            "uid" to uid,
                            "name" to fullName,
                            "email" to emailText,
                            "role" to role,
                            "pairCode" to pairCode,
                            "createdAt" to System.currentTimeMillis()
                        )

                        db.collection("users")
                            .document(uid)
                            .set(user)
                            .addOnSuccessListener {

                                Toast.makeText(
                                    this,
                                    "Account Created Successfully",
                                    Toast.LENGTH_LONG
                                ).show()
                                ttsManager.speakNow("Account created successfully")

                                startActivity(
                                    Intent(
                                        this,
                                        LoginActivity::class.java
                                    )
                                )

                                finish()

                            }
                            .addOnFailureListener {

                                Toast.makeText(
                                    this,
                                    it.message,
                                    Toast.LENGTH_LONG
                                ).show()
                                ttsManager.speakNow("Error creating account")

                            }

                    } else {

                        Toast.makeText(
                            this,
                            task.exception?.message,
                            Toast.LENGTH_LONG
                        ).show()
                        ttsManager.speakNow("Registration failed")

                    }

                }

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}