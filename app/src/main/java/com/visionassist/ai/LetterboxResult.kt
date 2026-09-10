package com.visionassist.ai

import java.nio.FloatBuffer

data class LetterboxResult(
    val buffer: FloatBuffer,
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val originalWidth: Int,
    val originalHeight: Int
)