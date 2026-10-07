package com.livevip.app.core

import kotlin.math.max
import kotlin.math.min

enum class FitMode { FIT, FILL }

/**
 * Single source of truth for geometry. The preview renderer AND the encoder
 * compositor consume the exact same instance, which guarantees
 * "what the user sees" == "what the encoder produces".
 */
data class CompositionState(
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 1280,
    val outputHeight: Int = 720,
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotation: Float = 0f,
    val fitMode: FitMode = FitMode.FIT
) {
    /** Base scale factors (normalized device coords) preserving the source aspect ratio. */
    fun baseScale(): Pair<Float, Float> {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            return 1f to 1f
        }
        val srcAr = sourceWidth.toFloat() / sourceHeight.toFloat()
        val outAr = outputWidth.toFloat() / outputHeight.toFloat()
        return if (fitMode == FitMode.FIT) {
            if (srcAr > outAr) 1f to (outAr / srcAr) else (srcAr / outAr) to 1f
        } else {
            if (srcAr > outAr) (srcAr / outAr) to 1f else 1f to (outAr / srcAr)
        }
    }

    /** Column-major 4x4 matrix applied to the full-screen quad. */
    fun toMatrix(): FloatArray {
        val (bx, by) = baseScale()
        val sx = bx * scale
        val sy = by * scale
        val r = Math.toRadians(rotation.toDouble())
        val c = Math.cos(r).toFloat()
        val s = Math.sin(r).toFloat()
        return floatArrayOf(
            sx * c, sx * s, 0f, 0f,
            -sy * s, sy * c, 0f, 0f,
            0f, 0f, 1f, 0f,
            translationX, translationY, 0f, 1f
        )
    }

    fun clamped(): CompositionState {
        val limit = 4f
        val s = min(max(scale, 0.2f), limit)
        // Keep at least a part of the video on canvas.
        val tx = min(max(translationX, -2f), 2f)
        val ty = min(max(translationY, -2f), 2f)
        return copy(scale = s, translationX = tx, translationY = ty)
    }

    fun reset(): CompositionState =
        copy(scale = 1f, translationX = 0f, translationY = 0f, rotation = 0f, fitMode = FitMode.FIT)
}
