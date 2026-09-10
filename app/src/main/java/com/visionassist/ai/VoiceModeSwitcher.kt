package com.visionassist.ai

import android.app.Activity

/**
 * بديل MODE_KEYWORDS في الكود البايثون الأصلي - مشترك بين كل الشاشات
 * عشان تقدر تغيّر المود بالصوت من أي شاشة إنت فيها، مش بس الشاشة الرئيسية.
 */
object VoiceModeSwitcher {

    val modeKeywords: Map<String, Class<out Activity>> = mapOf(
        "object" to ObjectDetectionExternalActivity::class.java,
        "describe" to ObjectDetectionExternalActivity::class.java,
        "reading" to OCRActivity::class.java,
        "read" to OCRActivity::class.java,
        "money" to CurrencyActivity::class.java,
        "cash" to CurrencyActivity::class.java,
        "currency" to CurrencyActivity::class.java,
        "navigation" to NavigationActivity::class.java,
        "navigate" to NavigationActivity::class.java,
        "gps" to NavigationActivity::class.java,
        "face" to FaceRecognitionActivity::class.java,
        "person" to FaceRecognitionActivity::class.java,
        "sos" to SOSActivity::class.java,
        "emergency" to SOSActivity::class.java,
        "help" to SOSActivity::class.java,
        "setting" to SettingsActivity::class.java
    )

    /** بيدور على أول كلمة مفتاحية موجودة في النص المسموع، ويرجع الشاشة المطابقة لها (أو null) */
    fun matchMode(spokenText: String): Class<out Activity>? {
        val lower = spokenText.lowercase()
        for ((keyword, activityClass) in modeKeywords) {
            if (lower.contains(keyword)) return activityClass
        }
        return null
    }
}