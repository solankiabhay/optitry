package com.optitry.app.eyewear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow

class FramePhotoTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val WHITE = argb(250, 250, 250)
    private val BLACK = argb(20, 20, 24)

    private class Img(val w: Int, val h: Int, val px: IntArray)

    /** Draws a front-on frame photo: two lenses (super-ellipse openings) in a rim, a bridge bar, plain background. */
    private fun render(
        pxPerMm: Float = 6f, apW: Float = 52f, apH: Float = 38f, n: Float = 3.5f, taper: Float = 0f, lift: Float = 0f,
        rim: Float = 4f, bridge: Float = 18f, margin: Int = 70, bg: Int = WHITE, lensFill: Int? = null,
        rimColour: (x: Int, y: Int, cy: Float) -> Int = { _, _, _ -> BLACK }
    ): Img {
        val frameMm = 2 * (apW + 2 * rim) + bridge
        val w = (frameMm * pxPerMm).toInt() + 2 * margin
        val h = ((apH + 2 * rim) * pxPerMm).toInt() + 2 * margin
        val px = IntArray(w * h) { bg }
        val cy = h / 2f
        for (y in 0 until h) for (x in 0 until w) {
            var out = bg
            for (s in intArrayOf(-1, 1)) {
                val cx = w / 2f + s * (bridge / 2 + rim + apW / 2) * pxPerMm
                val u = s * (x - cx) / pxPerMm
                val v = -(y - cy) / pxPerMm
                val vAdj = v - lift * (kotlin.math.max(0f, u) / (apW / 2)).pow(2) // only the outer corner rises
                val uAdj = u / (1f + taper * (v / (apH / 2)))
                fun inside(a: Float, b: Float) = abs(uAdj / a).pow(n) + abs(vAdj / b).pow(n) <= 1f
                if (inside(apW / 2, apH / 2)) out = lensFill ?: bg
                else if (inside(apW / 2 + rim, apH / 2 + rim)) out = rimColour(x, y, cy)
            }
            val barY = cy - apH * 0.25f * pxPerMm
            if (abs(x - w / 2f) <= bridge / 2 * pxPerMm + 1 && abs(y - barY) <= rim * pxPerMm / 2) out = rimColour(x, y, cy)
            px[y * w + x] = out
        }
        return Img(w, h, px)
    }

    private fun analyze(img: Img, cat: Category = Category.EYEGLASSES, hints: PhotoHints = PhotoHints.parse("52-18-140"), choice: PatternChoice = PatternChoice.AUTO) =
        FramePhotoAnalyzer.analyze(img.px, img.w, img.h, "my-test", "Test", cat, hints, choice)

    private fun ok(r: PhotoResult): FrameSpec { assertTrue("expected Ok but was ${(r as? PhotoResult.Fail)?.reason}", r is PhotoResult.Ok); return (r as PhotoResult.Ok).spec }
    private fun chan(c: Int) = intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    private fun near(c: Int, r: Int, g: Int, b: Int, tol: Int): Boolean { val a = chan(c); return abs(a[0] - r) <= tol && abs(a[1] - g) <= tol && abs(a[2] - b) <= tol }

    @Test fun blackPlasticRectangleIsMeasuredAccurately() {
        val spec = ok(analyze(render(n = 3.5f)))
        assertTrue("width ${spec.shape.width}", spec.shape.width in 5.35f..5.85f)
        assertTrue("height ${spec.shape.height}", spec.shape.height in 3.95f..4.45f)
        assertTrue("squareness ${spec.shape.squareness}", spec.shape.squareness in 2.7f..4.2f)
        assertEquals(RimStyle.ACETATE, spec.rim)
        assertTrue("rim ${spec.rimWidth}", spec.rimWidth in 0.34f..0.46f)
        assertTrue("colour ${chan(spec.frameColor).toList()}", near(spec.frameColor, 20, 20, 24, 30))
        assertEquals(Pattern.SOLID, spec.pattern)
        assertTrue(spec.bridgeWidth in 1.7f..2.1f)
    }

    @Test fun thinGoldRoundWireFrameIsDetectedAsMetal() {
        val gold = argb(214, 178, 94)
        val spec = ok(analyze(render(apW = 48f, apH = 48f, n = 2f, rim = 1.8f) { _, _, _ -> gold }, hints = PhotoHints.parse("48-18")))
        assertEquals(RimStyle.WIRE, spec.rim)
        assertTrue(spec.metallic)
        assertTrue("squareness ${spec.shape.squareness}", spec.shape.squareness in 2.0f..2.7f)
        assertTrue("width ${spec.shape.width}", spec.shape.width in 4.8f..5.2f)
        assertTrue("rim ${spec.rimWidth}", spec.rimWidth in 0.15f..0.22f)
        assertTrue("colour ${chan(spec.frameColor).toList()}", near(spec.frameColor, 214, 178, 94, 25))
    }

    @Test fun tortoiseTextureIsRecognised() {
        val spec = ok(analyze(render { x, y, _ ->
            val h = ((x / 9) * 73856093) xor ((y / 9) * 19349663)
            if ((h ushr 3) % 3 == 0) argb(222, 160, 78) else argb(110, 58, 22)
        }))
        assertEquals(Pattern.TORTOISE, spec.pattern)
        val lumBase = chan(spec.frameColor).sum(); val lumAcc = chan(spec.accentColor).sum()
        assertTrue("base should be darker than accent", lumBase < lumAcc)
    }

    @Test fun twoToneNavyAndSkyBecomesAGradient() {
        val navy = argb(28, 48, 98); val sky = argb(140, 180, 222)
        val spec = ok(analyze(render { _, y, cy -> if (y < cy) navy else sky }))
        assertEquals(Pattern.GRADIENT, spec.pattern)
        assertTrue("top ${chan(spec.frameColor).toList()}", near(spec.frameColor, 28, 48, 98, 30))
        assertTrue("bottom ${chan(spec.accentColor).toList()}", near(spec.accentColor, 140, 180, 222, 35))
    }

    @Test fun manualPatternChoiceOverridesAutoDetection() {
        assertEquals(Pattern.CRYSTAL, ok(analyze(render(), choice = PatternChoice.CRYSTAL)).pattern)
        assertEquals(Pattern.SOLID, ok(analyze(render { x, y, _ -> if (((x / 9) xor (y / 9)) % 2 == 0) argb(222, 160, 78) else argb(110, 58, 22) }, choice = PatternChoice.SOLID)).pattern)
    }

    @Test fun catEyeLiftAndTaperAreRecovered() {
        val spec = ok(analyze(render(apW = 53f, apH = 40f, n = 2.7f, taper = 0.10f, lift = 5f), hints = PhotoHints.parse("53-17")))
        assertTrue("lift ${spec.shape.lift}", spec.shape.lift > 0.2f)
        assertTrue("taper ${spec.shape.taper}", spec.shape.taper > 0.04f)
    }

    @Test fun badPhotosAreRejectedWithAHelpfulMessage() {
        val cropped = analyze(render(margin = 0))
        assertTrue(cropped is PhotoResult.Fail && (cropped as PhotoResult.Fail).reason.contains("kata"))
        val tiny = analyze(render(pxPerMm = 2f, margin = 420))
        assertTrue(tiny is PhotoResult.Fail && (tiny as PhotoResult.Fail).reason.contains("chhota"))
        val blank = FramePhotoAnalyzer.analyze(IntArray(300 * 300) { WHITE }, 300, 300, "x", "x", Category.EYEGLASSES)
        assertTrue(blank is PhotoResult.Fail)
    }

    @Test fun darkTintedLensesWorkAsSunglasses() {
        val spec = ok(analyze(render(lensFill = argb(40, 44, 52)), cat = Category.SUNGLASSES))
        assertTrue("width ${spec.shape.width}", spec.shape.width in 4.9f..5.8f)
        assertTrue("tint opacity ${spec.tint.opacity}", spec.tint.opacity >= 0.55f)
        assertEquals(Category.SUNGLASSES, spec.category)
    }

    @Test fun sizeTextIsParsed() {
        assertEquals(52f, PhotoHints.parse("52-18-140").lensMm!!, 0f)
        assertEquals(18f, PhotoHints.parse("52 18 140").bridgeMm!!, 0f)
        assertEquals(50f, PhotoHints.parse("50/20").lensMm!!, 0f)
        assertEquals(null, PhotoHints.parse("hello").lensMm)
        assertEquals(null, PhotoHints.parse("7").lensMm)
    }

    @Test fun framesSurviveSavingAndLoading() {
        val spec = ok(analyze(render(apW = 53f, apH = 40f, n = 2.7f, taper = 0.10f, lift = 5f), hints = PhotoHints.parse("53-17")))
        assertEquals(spec, FrameCodec.decode(FrameCodec.encode(spec)))
        val hexagon = FrameCatalog.all.first { it.shape.facets == 6 }
        assertEquals(hexagon, FrameCodec.decode(FrameCodec.encode(hexagon)))
        assertEquals(null, FrameCodec.decode("garbage|line"))
        val file = File.createTempFile("my_frames", ".txt")
        val store = MyFrameStore(file)
        store.add(spec.copy(id = "my-1", name = "A|B")); store.add(spec.copy(id = "my-2", name = "Two"))
        assertEquals(listOf("my-1", "my-2"), store.load().map { it.id })
        assertEquals("A B", store.load()[0].name) // the field separator is stripped from names
        store.remove("my-1")
        assertEquals(listOf("my-2"), MyFrameStore(file).load().map { it.id })
        file.delete()
    }

    @Test fun anyFrameThePhotoEngineCanProduceIsSafeIn3D() {
        val rnd = Random(7)
        fun f(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)
        repeat(250) { i ->
            val rimAcetate = rnd.nextBoolean()
            val rim = if (rimAcetate) RimStyle.ACETATE else RimStyle.WIRE
            val spec = FrameSpec(
                id = "r$i", name = "r$i", category = Category.EYEGLASSES,
                shape = LensShape(f(4.7f, 5.8f), f(3.6f, 5.1f), f(2.0f, 4.2f), taper = f(-0.13f, 0.10f), lift = if (rnd.nextBoolean()) 0f else f(0f, 0.5f), topBoost = if (rimAcetate && rnd.nextBoolean()) f(0.3f, 1.0f) else 0f),
                rim = rim, frameColor = argb(60, 60, 60), accentColor = argb(200, 200, 200), tint = LensTint(argb(190, 215, 225), 0.07f),
                rimWidth = if (rimAcetate) f(0.34f, 0.62f) else f(0.15f, 0.22f), bridgeWidth = f(1.7f, 2.1f)
            )
            val g = EyewearGeometry.build(spec)
            for (mesh in listOf(g.frame, g.lenses)) for (v in 0 until mesh.vertexCount) {
                val p = mesh.position(v)
                assertTrue("$i non-finite vertex", p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
                assertTrue("$i vertex out of range $p", abs(p.x) < 9.0f && p.y > -1.2f && p.y < 5.9f)
            }
            for (v in 0 until g.frame.vertexCount) {
                if (g.frame.vertices[v * MeshData.STRIDE_FLOATS + 6] != EyewearGeometry.PART_TEMPLE) continue
                val p = g.frame.position(v)
                assertTrue("$i arm inside head at $p", !Occluders.insideHead(p.x, p.y, p.z))
                assertTrue("$i arm inside ear at $p", !Occluders.insideEar(p.x, p.y, p.z, if (p.x > 0) 1f else -1f, 0.9f))
            }
            assertNotNull(g.hingeY)
        }
    }
}
