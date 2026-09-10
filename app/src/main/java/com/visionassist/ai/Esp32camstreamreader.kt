package com.visionassist.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * بديل ما كان بيحصل في المتصفح (http://IP:81/stream) بس جوا التطبيق نفسه.
 * ملحوظتين مهمتين اتعلمناهم من التجربة:
 * 1) readTimeout=0 (من غير مهلة) - بث MJPEG مستمر طبيعي يكون فيه فجوات صغيرة بين الفريمات،
 *    وأي مهلة محددة كانت بتقفل الاتصال غلط في اللحظات دي.
 * 2) لازم نتأكد إن أي اتصال قديم بيتقفل فعليًا (مش بس نوقف الخيط) عشان الكاميرا
 *    (بتسمح باتصال واحد بس) تسمح لشاشة تانية تتصل بسرعة.
 */
class Esp32CamStreamReader {

    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private var thread: Thread? = null

    fun start(streamUrl: String, onFrame: (Bitmap) -> Unit, onError: (Exception) -> Unit) {
        stop()
        running = true
        Log.d("Esp32CamStreamReader", "DIAGNOSTIC: بدء الاتصال بـ $streamUrl")

        thread = Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(streamUrl)
                conn = url.openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 0 // من غير مهلة - بث مستمر
                conn.useCaches = false
                conn.doInput = true

                Log.d("Esp32CamStreamReader", "DIAGNOSTIC: بنعمل connect()...")
                conn.connect()

                val responseCode = conn.responseCode
                Log.d("Esp32CamStreamReader", "DIAGNOSTIC: connect() نجح! response code = $responseCode")
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP Error: $responseCode")
                }

                val input = BufferedInputStream(conn.inputStream, 32 * 1024)
                val buffer = ByteArray(16 * 1024)
                val frameBuffer = ByteArrayOutputStream(100 * 1024)

                var inFrame = false
                var previousByte = -1
                var frameCount = 0

                while (running) {
                    val count = input.read(buffer)
                    if (count == -1) throw IOException("ESP32 stream ended")
                    if (count == 0) continue

                    for (index in 0 until count) {
                        val currentByte = buffer[index].toInt() and 0xFF

                        if (!inFrame) {
                            if (previousByte == 0xFF && currentByte == 0xD8) {
                                inFrame = true
                                frameBuffer.reset()
                                frameBuffer.write(0xFF)
                                frameBuffer.write(0xD8)
                                previousByte = -1
                                continue
                            }
                        } else {
                            frameBuffer.write(currentByte)

                            if (previousByte == 0xFF && currentByte == 0xD9) {
                                val jpegBytes = frameBuffer.toByteArray()
                                frameBuffer.reset()
                                inFrame = false

                                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                if (bitmap != null) {
                                    frameCount++
                                    if (frameCount <= 5 || frameCount % 30 == 0) {
                                        Log.d("Esp32CamStreamReader", "DIAGNOSTIC: Frame #$frameCount size=${jpegBytes.size} bitmap=${bitmap.width}x${bitmap.height}")
                                    }
                                    try {
                                        onFrame(bitmap)
                                    } catch (e: Exception) {
                                        Log.e("Esp32CamStreamReader", "DIAGNOSTIC: onFrame error", e)
                                        bitmap.recycle()
                                    }
                                } else {
                                    Log.w("Esp32CamStreamReader", "DIAGNOSTIC: Bitmap decode failed, JPEG size=${jpegBytes.size}")
                                }

                                previousByte = -1
                                continue
                            }
                        }
                        previousByte = currentByte
                    }
                }
                input.close()

            } catch (e: Exception) {
                if (running) {
                    Log.e("Esp32CamStreamReader", "DIAGNOSTIC: stream error", e)
                    try { onError(e) } catch (_: Exception) {}
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                if (connection === conn) connection = null
                Log.d("Esp32CamStreamReader", "DIAGNOSTIC: connection closed")
            }
        }.apply { name = "ESP32-CAM-Stream" }

        thread?.start()
    }

    fun stop() {
        Log.d("Esp32CamStreamReader", "DIAGNOSTIC: stopping stream")
        running = false
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
    }
}