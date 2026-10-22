package com.optitry.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Rigid head pose from MediaPipe's facial transformation matrix: canonical face model space (cm)
 * to the virtual camera space (right-handed, +Y up, camera looks down -Z, units cm).
 */
class FacePose(val rotation: Quat, val translation: Vec3, val scale: Float) {

    fun toMatrix(): FloatArray = Mat4.fromPose(rotation, scale, translation)

    /** Debug/UI helper in degrees: yaw (toward +X positive), pitch (up positive), roll (about the view axis). */
    fun eulerDegrees(): Triple<Float, Float, Float> {
        val r = rotation.toRotation()
        val pitch = Math.toDegrees(asin((-r[7]).toDouble().coerceIn(-1.0, 1.0))).toFloat()
        val yaw = Math.toDegrees(atan2(r[2].toDouble(), r[8].toDouble())).toFloat()
        val roll = Math.toDegrees(atan2(r[3].toDouble(), r[4].toDouble())).toFloat()
        return Triple(yaw, pitch, roll)
    }

    companion object {
        const val MIN_SCALE = 0.3f
        const val MAX_SCALE = 4f
        const val MIN_DISTANCE_CM = 8f
        const val MAX_DISTANCE_CM = 250f

        /**
         * Decodes a 16-float matrix. MediaPipe emits column-major data, but the layout is detected
         * from the bottom row so a row-major export is handled as well. Returns null for matrices that
         * are not a plausible similarity transform of a face in front of the camera.
         */
        fun fromMatrix(m: FloatArray?): FacePose? {
            if (m == null || m.size != 16 || m.any { !it.isFinite() }) return null
            val colMajor = abs(m[3]) + abs(m[7]) + abs(m[11]) < 1e-3f && abs(m[15] - 1f) < 1e-3f
            val rowMajor = abs(m[12]) + abs(m[13]) + abs(m[14]) < 1e-3f && abs(m[15] - 1f) < 1e-3f
            val c0: Vec3; val c1: Vec3; val c2: Vec3; val t: Vec3
            when {
                colMajor -> {
                    c0 = Vec3(m[0], m[1], m[2]); c1 = Vec3(m[4], m[5], m[6]); c2 = Vec3(m[8], m[9], m[10])
                    t = Vec3(m[12], m[13], m[14])
                }
                rowMajor -> {
                    c0 = Vec3(m[0], m[4], m[8]); c1 = Vec3(m[1], m[5], m[9]); c2 = Vec3(m[2], m[6], m[10])
                    t = Vec3(m[3], m[7], m[11])
                }
                else -> return null
            }
            val s0 = c0.length(); val s1 = c1.length(); val s2 = c2.length()
            val s = (s0 + s1 + s2) / 3f
            if (s < MIN_SCALE || s > MAX_SCALE) return null
            if (abs(s0 - s) > 0.15f * s || abs(s1 - s) > 0.15f * s || abs(s2 - s) > 0.15f * s) return null
            val x = c0 * (1f / s0); val y = c1 * (1f / s1); val z = c2 * (1f / s2)
            // Reject reflections, then re-orthogonalise (Gram-Schmidt) before extracting the quaternion.
            if (x.cross(y).dot(z) <= 0f) return null
            val zz = x.cross(y).normalized()
            val yy = zz.cross(x).normalized()
            val r = floatArrayOf(x.x, yy.x, zz.x, x.y, yy.y, zz.y, x.z, yy.z, zz.z)
            val dist = -t.z
            if (dist < MIN_DISTANCE_CM || dist > MAX_DISTANCE_CM) return null
            return FacePose(Quat.fromRotation(r), t, s)
        }
    }
}
