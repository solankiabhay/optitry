package com.optitry.app.core

import kotlin.math.hypot

/** One eye in normalised image coordinates (u, v in 0..1, v down). [contour] is u0,v0,u1,v1,... of the eyelid opening, in loop order. */
class Eye(val u: Float, val v: Float, val radiusPx: Float, val contour: FloatArray)

/** [right] is the person's right eye (it appears on the left of an un-mirrored camera image). */
class Eyes(val right: Eye, val left: Eye)

/** Builds [Eyes] from the 478 MediaPipe face landmarks (468 mesh + 10 iris). Pure maths so it is unit-testable. */
object EyeExtractor {
    const val MIN_LANDMARKS = 478
    private val RIGHT_CONTOUR = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    private val LEFT_CONTOUR = intArrayOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398)
    private const val RIGHT_IRIS = 468
    private const val LEFT_IRIS = 473

    fun extract(x: FloatArray, y: FloatArray, imageW: Int, imageH: Int): Eyes? {
        if (x.size < MIN_LANDMARKS || y.size < MIN_LANDMARKS || imageW <= 0 || imageH <= 0) return null
        val right = eye(x, y, imageW, imageH, RIGHT_IRIS, RIGHT_CONTOUR) ?: return null
        val left = eye(x, y, imageW, imageH, LEFT_IRIS, LEFT_CONTOUR) ?: return null
        return Eyes(right, left)
    }

    private fun eye(x: FloatArray, y: FloatArray, w: Int, h: Int, iris: Int, contour: IntArray): Eye? {
        val cx = x[iris]; val cy = y[iris]
        var r = 0f
        for (k in 1..4) r += hypot((x[iris + k] - cx) * w, (y[iris + k] - cy) * h)
        r /= 4f
        if (!cx.isFinite() || !cy.isFinite() || !r.isFinite() || r < 1f || r > minOf(w, h) * 0.25f) return null
        val c = FloatArray(contour.size * 2)
        for ((i, idx) in contour.withIndex()) {
            if (!x[idx].isFinite() || !y[idx].isFinite()) return null
            c[i * 2] = x[idx]; c[i * 2 + 1] = y[idx]
        }
        return Eye(cx, cy, r, c)
    }
}

/** Image -> on-screen mapping used for 2D overlays (same crop / mirror as the camera background quad). */
object EyeMapping {
    /** Pixels on screen per image pixel. */
    fun scale(imageW: Int, imageH: Int, viewW: Int, viewH: Int, fit: FitMode): Float {
        val (vx, _) = ViewportMapping.visibleFraction(imageW, imageH, viewW, viewH, fit)
        return viewW / (imageW * vx)
    }

    /** Normalised image point -> view pixel (origin top-left, y down). */
    fun toView(u: Float, v: Float, imageW: Int, imageH: Int, viewW: Int, viewH: Int, fit: FitMode, mirror: Boolean): FloatArray {
        val s = scale(imageW, imageH, viewW, viewH, fit)
        var px = viewW / 2f + (u - 0.5f) * imageW * s
        val py = viewH / 2f + (v - 0.5f) * imageH * s
        if (mirror) px = viewW - px
        return floatArrayOf(px, py)
    }

}

/** Light smoothing so the lens does not shimmer; fast moves (blinks, head turns) still pass through with little lag. */
class EyeSmoother {
    private class EyeFilters(n: Int) {
        val u = OneEuroFilter(3.0f, 0.6f); val v = OneEuroFilter(3.0f, 0.6f); val r = OneEuroFilter(1.5f, 0.2f)
        val c = Array(n) { OneEuroFilter(6.0f, 1.0f) }
        fun reset() { u.reset(); v.reset(); r.reset(); c.forEach { it.reset() } }
    }
    private val right = EyeFilters(32)
    private val left = EyeFilters(32)

    fun reset() { right.reset(); left.reset() }

    fun update(eyes: Eyes, dtSeconds: Float): Eyes = Eyes(apply(right, eyes.right, dtSeconds), apply(left, eyes.left, dtSeconds))

    private fun apply(f: EyeFilters, e: Eye, dt: Float): Eye {
        val c = FloatArray(e.contour.size) { i -> f.c[i].filter(e.contour[i], dt) }
        return Eye(f.u.filter(e.u, dt), f.v.filter(e.v, dt), f.r.filter(e.radiusPx, dt), c)
    }
}
