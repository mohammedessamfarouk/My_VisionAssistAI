package com.visionassist.ai

import android.content.Context

/**
 * بديل تثبيت الـ IPs جوا كود كل شاشة - دلوقتي بتتخزن في مكان واحد (SharedPreferences)
 * وأي شاشة بتقراها وقت التشغيل. لما تغيّر شبكة (زي هوت سبوت التليفون)، تغيّرهم من شاشة
 * الإعدادات بس، من غير أي Rebuild خالص.
 */
object NetworkConfig {

    private const val PREFS_NAME = "network_prefs"
    private const val KEY_CAMERA_IP = "camera_ip"
    private const val KEY_SENSOR_IP = "sensor_ip"

    // القيم الافتراضية لو المستخدم لسه ما غيّرش حاجة
    private const val DEFAULT_CAMERA_IP = "192.168.1.19"
    private const val DEFAULT_SENSOR_IP = "192.168.1.17"

    fun getCameraIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CAMERA_IP, DEFAULT_CAMERA_IP) ?: DEFAULT_CAMERA_IP
    }

    fun setCameraIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CAMERA_IP, ip).apply()
    }

    fun getSensorIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SENSOR_IP, DEFAULT_SENSOR_IP) ?: DEFAULT_SENSOR_IP
    }

    fun setSensorIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SENSOR_IP, ip).apply()
    }
}