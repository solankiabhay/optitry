package com.optitry.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small pure-Kotlin 3D math helpers (no android.* so they can be unit-tested on the JVM). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) Vec3(0f, 0f, 1f) else Vec3(x / l, y / l, z / l)
    }
}

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun dot(o: Quat) = x * o.x + y * o.y + z * o.z + w * o.w
    fun normalized(): Quat {
        val l = sqrt(dot(this))
        return if (l < 1e-9f) IDENTITY else Quat(x / l, y / l, z / l, w / l)
    }
    fun negated() = Quat(-x, -y, -z, -w)

    /** 3x3 rotation matrix, row-major r[row*3+col]. */
    fun toRotation(): FloatArray {
        val q = normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        return floatArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy)
        )
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /** From a row-major 3x3 rotation matrix r[row*3+col]. */
        fun fromRotation(r: FloatArray): Quat {
            val trace = r[0] + r[4] + r[8]
            val q = if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Quat((r[7] - r[5]) / s, (r[2] - r[6]) / s, (r[3] - r[1]) / s, 0.25f * s)
            } else if (r[0] > r[4] && r[0] > r[8]) {
                val s = sqrt(1f + r[0] - r[4] - r[8]) * 2f
                Quat(0.25f * s, (r[1] + r[3]) / s, (r[2] + r[6]) / s, (r[7] - r[5]) / s)
            } else if (r[4] > r[8]) {
                val s = sqrt(1f + r[4] - r[0] - r[8]) * 2f
                Quat((r[1] + r[3]) / s, 0.25f * s, (r[5] + r[7]) / s, (r[2] - r[6]) / s)
            } else {
                val s = sqrt(1f + r[8] - r[0] - r[4]) * 2f
                Quat((r[2] + r[6]) / s, (r[5] + r[7]) / s, 0.25f * s, (r[3] - r[1]) / s)
            }
            return q.normalized()
        }

        fun fromAxisAngle(axis: Vec3, radians: Float): Quat {
            val a = axis.normalized()
            val s = sin(radians / 2f)
            return Quat(a.x * s, a.y * s, a.z * s, cos(radians / 2f))
        }
    }
}

/** Column-major 4x4 matrices in OpenGL layout: m[col*4 + row]. */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** a * b (apply b first, then a). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    /** Standard OpenGL asymmetric frustum. */
    fun frustum(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = 2f * n / (r - l)
        m[5] = 2f * n / (t - b)
        m[8] = (r + l) / (r - l)
        m[9] = (t + b) / (t - b)
        m[10] = -(f + n) / (f - n)
        m[11] = -1f
        m[14] = -2f * f * n / (f - n)
        return m
    }

    /** Rotation * uniform scale + translation. */
    fun fromPose(q: Quat, scale: Float, t: Vec3): FloatArray {
        val r = q.toRotation()
        val m = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = r[row * 3 + col] * scale
        m[12] = t.x; m[13] = t.y; m[14] = t.z; m[15] = 1f
        return m
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        for (row in 0 until 4) out[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row]
        return out
    }

    fun isFinite(m: FloatArray): Boolean = m.all { it.isFinite() && abs(it) < 1e7f }
}
