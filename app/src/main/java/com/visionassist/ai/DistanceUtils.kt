// ============================================================
//  DistanceUtils.kt
// ============================================================
package com.visionassist.ai

import kotlin.math.max
import kotlin.math.min

/**
 * نفس منطق estimate_distance_m/get_direction/format_distance في الكود البايثون الأصلي.
 * القيم دي placeholder - لازم تتعاير على كاميرا تليفونك الفعلية (شوف التعليق تحت).
 *
 * طريقة المعايرة:
 * 1. حط جسم طوله معروف (مثلاً شخص طوله 1.70م) على بعد 2 متر بالظبط من الكاميرا.
 * 2. شغل الديتكشن وشوف ارتفاع الصندوق بالبكسل (pixel_height) في اللوج.
 * 3. احسب: FOCAL_LENGTH = (pixel_height * 2.0) / 1.70
 * 4. حط الرقم الناتج في FOCAL_LENGTH_SCENE تحت.
 */
object DistanceUtils {

    var FOCAL_LENGTH_SCENE = 500.0
    var FOCAL_LENGTH_NAV = 250.0 // نفس FOCAL_LENGTH_NAV في الكود الأصلي - جرب 350/400/450 لو الأرقام غلط

    val REAL_HEIGHT_M = mapOf(
        "person" to 1.70,
        "face" to 0.22,
        "default" to 0.50
    )

    fun estimateDistanceM(pixelHeight: Double, kind: String = "default"): Double {
        if (pixelHeight < 10) return 8.0
        val realHeight = REAL_HEIGHT_M[kind] ?: REAL_HEIGHT_M["default"]!!
        val distance = (realHeight * FOCAL_LENGTH_SCENE) / pixelHeight
        return Math.round(min(max(distance, 0.1), 12.0) * 10) / 10.0
    }

    /** بديل estimate_distance_nav في الكود الأصلي - بؤرة منفصلة عن Scene mode */
    fun estimateDistanceNav(pixelHeight: Double, kind: String = "default"): Double {
        if (pixelHeight < 10) return 8.0
        val realHeight = REAL_HEIGHT_M[kind] ?: REAL_HEIGHT_M["default"]!!
        val distance = (realHeight * FOCAL_LENGTH_NAV) / pixelHeight
        return Math.round(min(max(distance, 0.1), 12.0) * 10) / 10.0
    }

    fun formatDistance(distanceM: Double): String {
        return if (distanceM < 1.0) "${Math.round(distanceM * 100)} cm"
        else "%.1f m".format(distanceM)
    }

    fun getDirection(centerX: Float, width: Int): String {
        return when {
            centerX < width / 3f -> "on the left"
            centerX > 2 * width / 3f -> "on the right"
            else -> "straight ahead"
        }
    }
}