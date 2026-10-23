package com.optitry.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class EyesTest {
    private fun landmarks(w: Int, h: Int, irisR: Float): Pair<FloatArray, FloatArray> {
        val x = FloatArray(478) { 0.5f }; val y = FloatArray(478) { 0.5f }
        fun iris(center: Int, cx: Float, cy: Float) {
            x[center] = cx; y[center] = cy
            for (k in 0 until 4) { val a = k * Math.PI.toFloat() / 2f; x[center + 1 + k] = cx + irisR * cos(a) / w; y[center + 1 + k] = cy + irisR * sin(a) / h }
        }
        iris(468, 0.38f, 0.42f); iris(473, 0.62f, 0.42f)
        return x to y
    }

    @Test fun irisRadiusIsMeasuredInPixelsEvenForNonSquareImages() {
        val (x, y) = landmarks(720, 1280, 11f)
        val e = EyeExtractor.extract(x, y, 720, 1280)
        assertNotNull(e)
        assertEquals(11f, e!!.right.radiusPx, 0.05f)
        assertEquals(11f, e.left.radiusPx, 0.05f)
        assertEquals(0.38f, e.right.u, 1e-6f)
        assertEquals(32, e.right.contour.size)
    }

    @Test fun rejectsMissingIrisLandmarksAndImplausibleRadii() {
        assertNull(EyeExtractor.extract(FloatArray(468), FloatArray(468), 720, 1280)) // no iris points
        val (x, y) = landmarks(720, 1280, 400f)
        assertNull(EyeExtractor.extract(x, y, 720, 1280)) // radius larger than a quarter of the image
        val (x2, y2) = landmarks(720, 1280, 11f); x2[468] = Float.NaN
        assertNull(EyeExtractor.extract(x2, y2, 720, 1280))
    }

    @Test fun mappingMatchesTheCoverCropAndMirrorOfTheBackground() {
        // image centre always lands on the view centre
        val c = EyeMapping.toView(0.5f, 0.5f, 720, 1280, 1080, 2400, FitMode.COVER, mirror = false)
        assertEquals(540f, c[0], 0.01f); assertEquals(1200f, c[1], 0.01f)
        // mirrored selfie: a point left of centre ends up right of centre, same height
        val a = EyeMapping.toView(0.3f, 0.4f, 720, 1280, 1080, 2400, FitMode.COVER, mirror = false)
        val b = EyeMapping.toView(0.3f, 0.4f, 720, 1280, 1080, 2400, FitMode.COVER, mirror = true)
        assertEquals(1080f, a[0] + b[0], 0.01f); assertEquals(a[1], b[1], 1e-3f)
        // COVER: the image is scaled up to fill, so 100 image pixels span more than 100 view pixels here
        assertTrue(EyeMapping.scale(720, 1280, 1080, 2400, FitMode.COVER) > 1.5f)
        // scale is uniform: horizontal and vertical pixel distances agree
        val p0 = EyeMapping.toView(0.4f, 0.5f, 720, 1280, 1080, 2400, FitMode.COVER, false)
        val p1 = EyeMapping.toView(0.4f + 100f / 720f, 0.5f, 720, 1280, 1080, 2400, FitMode.COVER, false)
        val q1 = EyeMapping.toView(0.4f, 0.5f + 100f / 1280f, 720, 1280, 1080, 2400, FitMode.COVER, false)
        assertEquals(p1[0] - p0[0], q1[1] - p0[1], 0.01f)
    }

    @Test fun smootherKeepsStillEyesStillAndFollowsFastMoves() {
        val (x, y) = landmarks(720, 1280, 11f)
        val base = EyeExtractor.extract(x, y, 720, 1280)!!
        val s = EyeSmoother()
        var out = s.update(base, 1f / 30f)
        repeat(30) { out = s.update(base, 1f / 30f) }
        assertEquals(base.right.u, out.right.u, 1e-4f)
        val (x2, y2) = landmarks(720, 1280, 11f); for (i in 468..472) x2[i] += 0.1f
        val moved = EyeExtractor.extract(x2, y2, 720, 1280)!!
        repeat(6) { out = s.update(moved, 1f / 30f) }
        assertTrue("follows a fast move: ${out.right.u}", out.right.u > base.right.u + 0.05f)
    }

    @Test fun contactLensCatalogIsValid() {
        assertEquals(ContactLenses.all.size, ContactLenses.all.map { it.id }.toSet().size)
        assertEquals(ContactLenses.all.size, ContactLenses.all.map { it.name }.toSet().size)
        assertTrue(ContactLenses.all.size >= 10)
        assertTrue(ContactLenses.all.all { it.defaultOpacity in 0.3f..1f })
    }
}
