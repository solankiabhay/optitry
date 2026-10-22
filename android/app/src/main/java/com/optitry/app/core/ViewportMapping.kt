package com.optitry.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

enum class FitMode { COVER, CONTAIN }

/**
 * Builds the projection that maps MediaPipe's virtual camera (the space the facial transformation
 * matrix lives in) onto the on-screen image. MediaPipe's face geometry uses a 63 degree vertical field
 * of view with the aspect ratio of the analysed image. The visible part of that image depends on how
 * the preview is fitted into the view (COVER = FILL_CENTER crop, CONTAIN = FIT_CENTER letterbox).
 */
object ViewportMapping {
    const val MEDIAPIPE_VERTICAL_FOV_DEGREES = 63f
    const val NEAR = 1f
    const val FAR = 1000f

    /** Fractions (x, y) of the image that are visible on screen, centred. COVER crops, CONTAIN letterboxes (> 1). */
    fun visibleFraction(imageW: Int, imageH: Int, viewW: Int, viewH: Int, fit: FitMode): Pair<Float, Float> {
        require(imageW > 0 && imageH > 0 && viewW > 0 && viewH > 0) { "sizes must be positive" }
        val sx = viewW.toFloat() / imageW
        val sy = viewH.toFloat() / imageH
        val scale = if (fit == FitMode.COVER) max(sx, sy) else min(sx, sy)
        return Pair(viewW / (imageW * scale), viewH / (imageH * scale))
    }

    fun projection(imageW: Int, imageH: Int, viewW: Int, viewH: Int, fit: FitMode, mirror: Boolean): FloatArray {
        val (visibleX, visibleY) = visibleFraction(imageW, imageH, viewW, viewH, fit)
        val top = NEAR * tan(Math.toRadians(MEDIAPIPE_VERTICAL_FOV_DEGREES / 2.0)).toFloat()
        val right = top * imageW.toFloat() / imageH.toFloat()
        val m = Mat4.frustum(-right * visibleX, right * visibleX, -top * visibleY, top * visibleY, NEAR, FAR)
        if (mirror) { m[0] = -m[0]; m[4] = -m[4]; m[8] = -m[8]; m[12] = -m[12] } // selfie preview is mirrored
        return m
    }
}
