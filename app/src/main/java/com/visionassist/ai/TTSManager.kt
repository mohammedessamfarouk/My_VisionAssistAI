package com.visionassist.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TTSManager(context: Context) {

    private var isSpeaking = false
    private var lastText = ""
    private var lastSpokenTime = 0L
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) { isSpeaking = false }
            override fun onError(utteranceId: String?) { isSpeaking = false }
        })
    }

    fun isBusy(): Boolean = isSpeaking

    fun speakIfDue(text: String, cooldownMs: Long) {
        if (!isReady) return
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && text != lastText && now - lastSpokenTime > cooldownMs && !isSpeaking) {
            speakNow(text)
            lastText = text
            lastSpokenTime = now
        }
    }

    fun speakNow(text: String) {
        if (!isReady) return
        tts?.language = if (containsArabic(text)) Locale("ar") else Locale.US
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
    }

    /** بيتأكد هل النص فيه حروف عربية عشان نبدّل صوت النطق المناسب */
    private fun containsArabic(text: String): Boolean {
        return text.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F }
    }

    fun resetState() {
        lastText = ""
        lastSpokenTime = 0L
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}