package com.optitry.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** One Euro filter (Casiez et al. 2012): heavy smoothing when still, low latency when moving fast. */
class OneEuroFilter(
    private val minCutoffHz: Float,
    private val beta: Float,
    private val derivativeCutoffHz: Float = 1f
) {
    private var prev = 0f
    private var prevDerivative = 0f
    private var initialised = false

    fun reset() { initialised = false }

    fun filter(value: Float, dtSeconds: Float): Float {
        val dt = dtSeconds.coerceIn(1f / 240f, 0.25f)
        if (!initialised) {
            initialised = true; prev = value; prevDerivative = 0f
            return value
        }
        val rawDerivative = (value - prev) / dt
        val ad = alpha(dt, derivativeCutoffHz)
        val derivative = ad * rawDerivative + (1f - ad) * prevDerivative
        val cutoff = minCutoffHz + beta * abs(derivative)
        val a = alpha(dt, cutoff)
        val out = a * value + (1f - a) * prev
        prev = out; prevDerivative = derivative
        return out
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

/**
 * Smooths a stream of [FacePose]s. Translation (cm), log-scale and the rotation quaternion are
 * filtered independently with One Euro filters. Call from a single thread.
 * The defaults are reasonable starting values, not values tuned on a physical device.
 */
class PoseSmoother(
    translationMinCutoff: Float = 1.6f,
    translationBeta: Float = 0.45f,
    rotationMinCutoff: Float = 1.8f,
    rotationBeta: Float = 9f,
    private val resetGapSeconds: Float = 0.5f
) {
    private val tx = OneEuroFilter(translationMinCutoff, translationBeta)
    private val ty = OneEuroFilter(translationMinCutoff, translationBeta)
    private val tz = OneEuroFilter(translationMinCutoff * 0.6f, translationBeta)
    private val ls = OneEuroFilter(1.0f, 0.2f)
    private val qx = OneEuroFilter(rotationMinCutoff, rotationBeta)
    private val qy = OneEuroFilter(rotationMinCutoff, rotationBeta)
    private val qz = OneEuroFilter(rotationMinCutoff, rotationBeta)
    private val qw = OneEuroFilter(rotationMinCutoff, rotationBeta)
    private var lastNanos = Long.MIN_VALUE
    private var lastQuat: Quat? = null

    fun reset() {
        listOf(tx, ty, tz, ls, qx, qy, qz, qw).forEach { it.reset() }
        lastNanos = Long.MIN_VALUE
        lastQuat = null
    }

    fun update(pose: FacePose, timestampNanos: Long): FacePose {
        val dt = if (lastNanos == Long.MIN_VALUE) 1f / 30f else (timestampNanos - lastNanos) / 1e9f
        if (lastNanos != Long.MIN_VALUE && (dt > resetGapSeconds || dt < 0f)) reset()
        val firstSample = lastNanos == Long.MIN_VALUE
        lastNanos = timestampNanos
        val step = if (firstSample) 1f / 30f else dt

        var q = pose.rotation
        val previous = lastQuat
        if (previous != null && q.dot(previous) < 0f) q = q.negated() // keep the hemisphere continuous
        val fq = Quat(qx.filter(q.x, step), qy.filter(q.y, step), qz.filter(q.z, step), qw.filter(q.w, step)).normalized()
        lastQuat = fq
        val t = Vec3(tx.filter(pose.translation.x, step), ty.filter(pose.translation.y, step), tz.filter(pose.translation.z, step))
        val s = exp(ls.filter(ln(pose.scale), step))
        return FacePose(fq, t, s)
    }
}
