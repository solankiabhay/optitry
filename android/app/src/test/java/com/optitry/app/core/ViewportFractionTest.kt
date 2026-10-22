package com.optitry.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportFractionTest {
    @Test fun coverCropsTheLongerAxisOfTheImage() {
        // 720x1280 portrait image on a 1080x2400 (taller) screen: width is cropped, height is fully visible.
        val (vx, vy) = ViewportMapping.visibleFraction(720, 1280, 1080, 2400, FitMode.COVER)
        assertEquals(1f, vy, 1e-4f)
        assertEquals((1080f / 2400f) / (720f / 1280f), vx, 1e-4f)
    }

    @Test fun squareImageOnSquareViewIsFullyVisible() {
        val (vx, vy) = ViewportMapping.visibleFraction(500, 500, 900, 900, FitMode.COVER)
        assertEquals(1f, vx, 1e-4f)
        assertEquals(1f, vy, 1e-4f)
    }

    @Test fun containLetterboxesSoFractionsExceedOne() {
        val (vx, vy) = ViewportMapping.visibleFraction(1280, 720, 1000, 1000, FitMode.CONTAIN)
        assertEquals(1f, vx, 1e-4f)
        assertEquals(true, vy > 1f)
    }
}
