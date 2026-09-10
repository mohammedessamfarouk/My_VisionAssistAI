package com.visionassist.ai

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * بديل قراءة السيريال مباشرة - بيسأل الـ ESP32 العادي كل شوية مللي ثانية عن آخر قراءة
 * مسافة من حساس VL53L1X، عن طريق طلب HTTP بسيط لعنوان /distance.
 */
class DistanceSensorReader {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(ip: String, onDistance: (Int) -> Unit, onError: (Exception) -> Unit) {
        stop()
        running = true
        thread = Thread {
            val url = URL("http://$ip/distance")
            while (running) {
                try {
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 1000
                    connection.readTimeout = 1000

                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val text = reader.readLine()
                    reader.close()
                    connection.disconnect()

                    val mm = text?.trim()?.toIntOrNull()
                    if (mm != null) onDistance(mm)

                } catch (e: Exception) {
                    if (running) {
                        Log.e("DistanceSensorReader", "DIAGNOSTIC: read error", e)
                        onError(e)
                    }
                }

                try {
                    Thread.sleep(200) // 5 قراءات في الثانية تقريبًا
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        thread?.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}