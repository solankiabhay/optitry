package com.optitry.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

class CoreMathTest {
    private fun rot(axis: Vec3, deg: Float) = Quat.fromAxisAngle(axis, Math.toRadians(deg.toDouble()).toFloat())

    private fun colMajor(q: Quat, s: Float, t: Vec3) = Mat4.fromPose(q, s, t)

    private fun rowMajor(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) r[row * 4 + c] = m[c * 4 + row]
        return r
    }

    @Test fun quaternionRotationRoundTrip() {
        for (deg in listOf(0f, 15f, 70f, 120f, 179f, -45f)) for (axis in listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0.3f, 0.5f, 0.8f))) {
            val q = rot(axis, deg)
            val back = Quat.fromRotation(q.toRotation())
            assertEquals("round trip $deg", 1f, abs(q.dot(back)), 1e-4f)
        }
    }

    @Test fun poseDecodesColumnMajorMatrix() {
        val q = rot(Vec3(0f, 1f, 0f), 25f)
        val pose = FacePose.fromMatrix(colMajor(q, 1.08f, Vec3(2f, -1f, -45f)))
        assertNotNull(pose)
        assertEquals(1.08f, pose!!.scale, 1e-4f)
        assertEquals(-45f, pose.translation.z, 1e-4f)
        assertEquals(1f, abs(pose.rotation.dot(q)), 1e-4f)
        assertEquals(25f, pose.eulerDegrees().first, 0.1f)
    }

    @Test fun poseDecodesRowMajorMatrixToSameResult() {
        val m = colMajor(rot(Vec3(1f, 0f, 0f), -20f), 0.95f, Vec3(-3f, 2f, -60f))
        val a = FacePose.fromMatrix(m)!!
        val b = FacePose.fromMatrix(rowMajor(m))!!
        assertEquals(a.scale, b.scale, 1e-4f)
        assertEquals(1f, abs(a.rotation.dot(b.rotation)), 1e-4f)
        assertEquals(a.translation.x, b.translation.x, 1e-4f)
        assertEquals(a.translation.y, b.translation.y, 1e-4f)
        assertEquals(a.translation.z, b.translation.z, 1e-4f)
    }

    @Test fun poseRejectsImplausibleMatrices() {
        val good = colMajor(Quat.IDENTITY, 1f, Vec3(0f, 0f, -40f))
        assertNotNull(FacePose.fromMatrix(good))
        assertNull(FacePose.fromMatrix(null))
        assertNull(FacePose.fromMatrix(FloatArray(9)))
        assertNull("behind the camera", FacePose.fromMatrix(colMajor(Quat.IDENTITY, 1f, Vec3(0f, 0f, 40f))))
        assertNull("too close", FacePose.fromMatrix(colMajor(Quat.IDENTITY, 1f, Vec3(0f, 0f, -2f))))
        assertNull("absurd scale", FacePose.fromMatrix(colMajor(Quat.IDENTITY, 9f, Vec3(0f, 0f, -40f))))
        val nan = good.copyOf(); nan[5] = Float.NaN
        assertNull(FacePose.fromMatrix(nan))
        val mirrored = good.copyOf(); mirrored[0] = -mirrored[0]
        assertNull("reflection", FacePose.fromMatrix(mirrored))
    }

    @Test fun matrixMultiplyAndTransform() {
        val a = colMajor(rot(Vec3(0f, 0f, 1f), 90f), 2f, Vec3(1f, 2f, 3f))
        val p = Mat4.transformPoint(Mat4.multiply(Mat4.identity(), a), 1f, 0f, 0f)
        assertEquals(1f, p[0], 1e-4f); assertEquals(4f, p[1], 1e-4f); assertEquals(3f, p[2], 1e-4f)
    }

    @Test fun projectionMatchesPinholeMath() {
        val iw = 480; val ih = 640
        val proj = ViewportMapping.projection(iw, ih, iw, ih, FitMode.COVER, mirror = false)
        val tanHalf = tan(Math.toRadians(ViewportMapping.MEDIAPIPE_VERTICAL_FOV_DEGREES / 2.0)).toFloat()
        // point 40 cm away, 5 cm right and 3 cm up of the optical axis
        val clip = Mat4.transformPoint(proj, 5f, 3f, -40f)
        val ndcX = clip[0] / clip[3]; val ndcY = clip[1] / clip[3]
        assertEquals((5f / 40f) / (tanHalf * iw / ih), ndcX, 1e-4f)
        assertEquals((3f / 40f) / tanHalf, ndcY, 1e-4f)
    }

    @Test fun mirrorFlipsOnlyX() {
        val a = Mat4.transformPoint(ViewportMapping.projection(480, 640, 1080, 1440, FitMode.COVER, false), 4f, 2f, -50f)
        val b = Mat4.transformPoint(ViewportMapping.projection(480, 640, 1080, 1440, FitMode.COVER, true), 4f, 2f, -50f)
        assertEquals(-a[0] / a[3], b[0] / b[3], 1e-5f)
        assertEquals(a[1] / a[3], b[1] / b[3], 1e-5f)
    }

    @Test fun coverCropsAndContainLetterboxes() {
        // 4:3 portrait image shown in a much taller view: COVER crops the sides (visible fraction < 1), CONTAIN shows all of it.
        val cover = ViewportMapping.projection(480, 640, 600, 1500, FitMode.COVER, false)
        val contain = ViewportMapping.projection(480, 640, 600, 1500, FitMode.CONTAIN, false)
        val coverX = Mat4.transformPoint(cover, 1f, 0f, -10f).let { it[0] / it[3] }
        val containX = Mat4.transformPoint(contain, 1f, 0f, -10f).let { it[0] / it[3] }
        assertTrue("cover magnifies more than contain", abs(coverX) > abs(containX))
        // same aspect => identical
        val same = ViewportMapping.projection(480, 640, 960, 1280, FitMode.COVER, false)
        val same2 = ViewportMapping.projection(480, 640, 960, 1280, FitMode.CONTAIN, false)
        for (i in 0 until 16) assertEquals(same[i], same2[i], 1e-5f)
    }

    @Test fun oneEuroReducesStaticJitter() {
        val f = OneEuroFilter(1.0f, 0.0f)
        val rng = java.util.Random(7)
        var rawVar = 0.0; var outVar = 0.0; var n = 0
        for (i in 0 until 600) {
            val noisy = 10f + (rng.nextGaussian() * 0.2).toFloat()
            val out = f.filter(noisy, 1f / 30f)
            if (i > 60) { rawVar += (noisy - 10f) * (noisy - 10f); outVar += (out - 10f) * (out - 10f); n++ }
        }
        assertTrue("filtered variance ${outVar / n} must be well below raw ${rawVar / n}", outVar < rawVar * 0.25)
    }

    @Test fun oneEuroFollowsFastMotionWithLowLag() {
        val slow = OneEuroFilter(1.0f, 0.0f)
        val adaptive = OneEuroFilter(1.0f, 2.0f)
        var slowErr = 0f; var adaptiveErr = 0f
        for (i in 0 until 90) {
            val v = i * 0.5f // 15 units/s ramp at 30 fps
            slowErr = abs(slow.filter(v, 1f / 30f) - v)
            adaptiveErr = abs(adaptive.filter(v, 1f / 30f) - v)
        }
        assertTrue("adaptive lag $adaptiveErr < fixed-cutoff lag $slowErr", adaptiveErr < slowErr * 0.5f)
    }

    @Test fun poseSmootherKeepsRotationContinuousAcrossQuaternionSignFlip() {
        val s = PoseSmoother()
        var t = 0L
        var last: FacePose? = null
        for (i in 0 until 60) {
            val q = rot(Vec3(0f, 1f, 0f), i * 1.5f)
            val raw = FacePose(if (i % 2 == 0) q else q.negated(), Vec3(0f, 0f, -40f), 1f) // same rotation, flipping sign
            t += 33_000_000L
            last = s.update(raw, t)
        }
        val expected = rot(Vec3(0f, 1f, 0f), 59 * 1.5f)
        assertTrue("rotation still tracks the real angle", abs(last!!.rotation.dot(expected)) > 0.995f)
    }

    @Test fun poseSmootherResetsAfterTrackingGap() {
        val s = PoseSmoother()
        s.update(FacePose(Quat.IDENTITY, Vec3(0f, 0f, -40f), 1f), 0L)
        val out = s.update(FacePose(Quat.IDENTITY, Vec3(10f, 0f, -40f), 1f), 2_000_000_000L)
        assertEquals("snaps to new position after a >0.5 s gap", 10f, out.translation.x, 1e-4f)
    }
}
