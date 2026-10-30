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

    private class Img(val w: Int, val h: Int, val px: IntArray, val ap: BooleanArray)

    /**
     * Draws a front-on frame photo. [bridge] is the gap between the two lens openings (what "18" on the arm means), [rim] the rim wall.
     * Options: [arms] draws dark temple stubs seen THROUGH the lens, [chamfer] cuts the nose-side lower corner (D shape),
     * [doubleBridge] adds a brow bar plus the normal bridge, [lift]/[taper] shape a cat-eye.
     */
    private fun render(
        pxPerMm: Float = 6f, apW: Float = 52f, apH: Float = 38f, n: Float = 3.5f, taper: Float = 0f, lift: Float = 0f,
        rim: Float = 4f, bridge: Float = 18f, margin: Int = 70, bg: Int = WHITE, lensFill: Int? = null,
        arms: Boolean = false, chamfer: Boolean = false, doubleBridge: Boolean = false,
        rimColour: (x: Int, y: Int, cy: Float) -> Int = { _, _, _ -> BLACK }
    ): Img {
        val frameMm = 2 * apW + bridge + 2 * rim
        val w = (frameMm * pxPerMm).toInt() + 2 * margin
        val h = ((apH + 2 * rim) * pxPerMm).toInt() + 2 * margin
        val px = IntArray(w * h) { bg }
        val ap = BooleanArray(w * h)
        val cy = h / 2f
        for (y in 0 until h) for (x in 0 until w) {
            var out = bg
            for (s in intArrayOf(-1, 1)) {
                val cx = w / 2f + s * (bridge / 2 + apW / 2) * pxPerMm
                val u = s * (x - cx) / pxPerMm
                val v = -(y - cy) / pxPerMm
                val vAdj = v - lift * (kotlin.math.max(0f, u) / (apW / 2)).pow(2) // only the outer corner rises
                val uAdj = u / (1f + taper * (v / (apH / 2)))
                fun inside(a: Float, b: Float): Boolean {
                    if (abs(uAdj / a).pow(n) + abs(vAdj / b).pow(n) > 1f) return false
                    if (chamfer && uAdj < 0f && vAdj < 0f && (-uAdj / a) + (-vAdj / b) > 1.25f) return false // diagonal nose-side cut
                    return true
                }
                if (inside(apW / 2, apH / 2)) {
                    out = lensFill ?: bg
                    ap[y * w + x] = true
                    if (arms) {
                        // a temple seen through the lens: a bar from the outer rim inwards, in the upper-middle of the lens
                        if (u > apW * 0.08f && abs(v - apH * 0.12f) < 3.5f) { out = argb(40, 40, 44); ap[y * w + x] = false }
                    }
                } else {
                    // wall of the rim: outer outline = opening grown by the rim thickness
                    val grown = abs(uAdj / (apW / 2 + rim)).pow(n) + abs(vAdj / (apH / 2 + rim)).pow(n) <= 1f
                    if (grown && !(chamfer && uAdj < 0f && vAdj < 0f && (-uAdj / (apW / 2 + rim)) + (-vAdj / (apH / 2 + rim)) > 1.25f - 0.02f)) out = rimColour(x, y, cy)
                    else if (chamfer && grown) out = rimColour(x, y, cy)
                }
            }
            val barHalf = (bridge / 2 + rim) * pxPerMm
            val barY = cy - apH * 0.10f * pxPerMm
            if (abs(x - w / 2f) <= barHalf && abs(y - barY) <= rim * pxPerMm / 2) out = rimColour(x, y, cy)
            if (doubleBridge) {
                val topY = cy - (apH / 2 + rim) * pxPerMm + rim * pxPerMm * 0.55f
                if (abs(x - w / 2f) <= barHalf && abs(y - topY) <= rim * pxPerMm * 0.55f) out = rimColour(x, y, cy)
            }
            px[y * w + x] = out
        }
        return Img(w, h, px, ap)
    }

    private fun analyze(img: Img, cat: Category = Category.EYEGLASSES, hints: PhotoHints = PhotoHints.parse("52-18-140"), choice: PatternChoice = PatternChoice.AUTO) =
        FramePhotoAnalyzer.analyze(img.px, img.w, img.h, "my-test", "Test", cat, hints, choice)

    private fun ok(r: PhotoResult): FrameSpec { assertTrue("expected Ok but was ${(r as? PhotoResult.Fail)?.reason}", r is PhotoResult.Ok); return (r as PhotoResult.Ok).spec }
    private fun chan(c: Int) = intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    private fun near(c: Int, r: Int, g: Int, b: Int, tol: Int): Boolean { val a = chan(c); return abs(a[0] - r) <= tol && abs(a[1] - g) <= tol && abs(a[2] - b) <= tol }

    @Test fun blackPlasticRectangleIsMeasuredAccurately() {
        val spec = ok(analyze(render(n = 3.5f)))
        assertTrue("width ${spec.shape.width}", spec.shape.width in 5.0f..5.4f)   // 52 mm opening
        assertTrue("height ${spec.shape.height}", spec.shape.height in 3.65f..3.95f) // 38 mm opening
        assertEquals(RimStyle.ACETATE, spec.rim)
        assertTrue("rim ${spec.rimWidth}", spec.rimWidth in 0.34f..0.46f)
        assertTrue("colour ${chan(spec.frameColor).toList()}", near(spec.frameColor, 20, 20, 24, 30))
        assertEquals(Pattern.SOLID, spec.pattern)
        assertTrue("bridge ${spec.bridgeWidth}", spec.bridgeWidth in 1.65f..1.95f)  // 18 mm gap
        assertTrue(!spec.doubleBridge)
        assertNotNull(spec.shape.radial)
    }

    @Test fun thinGoldRoundWireFrameIsDetectedAsMetal() {
        val gold = argb(214, 178, 94)
        val spec = ok(analyze(render(apW = 48f, apH = 48f, n = 2f, rim = 1.8f) { _, _, _ -> gold }, hints = PhotoHints.parse("48-18")))
        assertEquals(RimStyle.WIRE, spec.rim)
        assertTrue(spec.metallic)
        assertTrue("width ${spec.shape.width}", spec.shape.width in 4.6f..5.0f)
        assertTrue("height ${spec.shape.height}", spec.shape.height in 4.6f..5.0f)
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

    @Test fun smokyClearPlasticIsDetectedAsCrystal() {
        // grey translucent acetate on white: low saturation, shading and highlights across the rim
        val spec = ok(analyze(render { x, y, cy ->
            val shade = ((x / 7) * 31 + (y / 7) * 17) % 40 - 20
            val base = if (y < cy - 60) 150 else 105
            argb((base + shade).coerceIn(0, 255), (base + shade).coerceIn(0, 255), (base + shade + 6).coerceIn(0, 255))
        }))
        assertEquals(Pattern.CRYSTAL, spec.pattern)
        assertEquals(Pattern.SOLID, ok(analyze(render { _, _, _ -> argb(120, 120, 126) })).pattern) // flat grey stays solid
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

    /** Intersection over union between the real opening and the outline the engine produced (right lens). */
    private fun iou(img: Img, spec: FrameSpec, pxPerMm: Float = 6f): Float {
        var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
        for (y in 0 until img.h) for (x in img.w / 2 until img.w) if (img.ap[y * img.w + x]) { x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
        val bx = (x0 + x1) / 2f; val by = (y0 + y1) / 2f
        val poly = spec.shape.contour(144).map { floatArrayOf(bx + it[0] * 10f * pxPerMm, by - it[1] * 10f * pxPerMm) }
        fun inPoly(px: Float, py: Float): Boolean {
            var c = false; var j = poly.size - 1
            for (i in poly.indices) {
                if ((poly[i][1] > py) != (poly[j][1] > py) && px < (poly[j][0] - poly[i][0]) * (py - poly[i][1]) / (poly[j][1] - poly[i][1]) + poly[i][0]) c = !c
                j = i
            }
            return c
        }
        var inter = 0; var union = 0
        for (y in (y0 - 10)..(y1 + 10)) for (x in (x0 - 10)..(x1 + 10)) {
            val t = img.ap[y * img.w + x] && x >= img.w / 2
            val m = inPoly(x + 0.5f, y + 0.5f)
            if (t && m) inter++; if (t || m) union++
        }
        return inter.toFloat() / union
    }

    @Test fun lensOutlineMatchesThePhotoClosely() {
        for ((name, img, size) in listOf(
            Triple("rounded rectangle", render(n = 3.5f), "52-18"),
            Triple("D shape with nose chamfer", render(apW = 54f, apH = 42f, n = 3.2f, chamfer = true), "54-18"),
            Triple("cat eye", render(apW = 53f, apH = 40f, n = 2.7f, taper = 0.10f, lift = 5f), "53-18"),
            Triple("round", render(apW = 48f, apH = 48f, n = 2f), "48-18")
        )) {
            val spec = ok(analyze(img, hints = PhotoHints.parse(size)))
            val q = iou(img, spec)
            assertTrue("$name: outline matches only ${(q * 100).toInt()}%", q >= 0.90f) // a convex outline cannot follow a strong cat-eye's concave corner
            val g = EyewearGeometry.build(spec) // and whatever the engine measures must stay inside the 3D engine's safe envelope
            for (v in 0 until g.frame.vertexCount) { val pt = g.frame.position(v); assertTrue("$name: vertex out of range $pt", abs(pt.x) < 8.9f && pt.y > -1.2f && pt.y < 5.95f) }
        }
    }

    @Test fun armsSeenThroughTheLensDoNotSpoilTheShape() {
        val plain = render(apW = 54f, apH = 42f, n = 3.2f, chamfer = true)
        val withArms = render(apW = 54f, apH = 42f, n = 3.2f, chamfer = true, arms = true)
        val a = ok(analyze(plain, hints = PhotoHints.parse("54-18")))
        val b = ok(analyze(withArms, hints = PhotoHints.parse("54-18")))
        assertTrue("width ${b.shape.width} vs ${a.shape.width}", abs(b.shape.width - a.shape.width) < 0.15f)
        assertTrue("height ${b.shape.height} vs ${a.shape.height}", abs(b.shape.height - a.shape.height) < 0.15f)
        assertTrue("outline with arms ${(iou(withArms, b) * 100).toInt()}% of the clean opening", iou(plain, b) >= 0.90f)
    }

    @Test fun pilotFramesWithABrowBarAreDetected() {
        val img = render(apW = 54f, apH = 42f, n = 3.2f, chamfer = true, doubleBridge = true, rimColour = { _, _, _ -> argb(110, 106, 112) })
        assertTrue(ok(analyze(img, hints = PhotoHints.parse("54-18"))).doubleBridge)
        assertTrue(!ok(analyze(render(apW = 54f, apH = 42f, n = 3.2f), hints = PhotoHints.parse("54-18"))).doubleBridge)
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
        assertTrue("width ${spec.shape.width}", spec.shape.width in 4.7f..5.8f)
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
        val spec = ok(analyze(render(apW = 54f, apH = 42f, n = 3.2f, chamfer = true, doubleBridge = true), hints = PhotoHints.parse("54-18")))
        assertNotNull(spec.shape.radial)
        assertEquals(spec, FrameCodec.decode(FrameCodec.encode(spec)))
        val hexagon = FrameCatalog.all.first { it.shape.facets == 6 }
        assertEquals(hexagon, FrameCodec.decode(FrameCodec.encode(hexagon)))
        assertEquals(null, FrameCodec.decode("garbage|line"))
        // frames saved by the previous app version (format F1, no outline, no brow bar) still load
        val old = FrameCodec.encode(hexagon).split("|").toMutableList()
        old[0] = "F1"; old[11] = old[11].substringBeforeLast(",")
        assertEquals(hexagon, FrameCodec.decode(old.joinToString("|")))
        val file = File.createTempFile("my_frames", ".txt")
        val store = MyFrameStore(file)
        store.add(spec.copy(id = "my-1", name = "A|B")); store.add(spec.copy(id = "my-2", name = "Two"))
        assertEquals(listOf("my-1", "my-2"), store.load().map { it.id })
        assertEquals("A B", store.load()[0].name) // the field separator is stripped from names
        assertEquals(spec.shape, store.load()[1].shape)
        store.remove("my-1")
        assertEquals(listOf("my-2"), MyFrameStore(file).load().map { it.id })
        file.delete()
    }

    @Test fun anyFrameThePhotoEngineCanProduceIsSafeIn3D() {
        val rnd = Random(7)
        fun f(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)
        repeat(300) { i ->
            val rimAcetate = rnd.nextBoolean()
            val rim = if (rimAcetate) RimStyle.ACETATE else RimStyle.WIRE
            // a random but star-shaped, smooth outline like the ones measured from photos
            val base = LensShape(f(4.4f, 5.9f), f(3.4f, 5.3f), f(2.0f, 4.5f), taper = f(-0.13f, 0.10f)) // no lift: measured outlines are centred on the opening's bounding box, like these
            val c = base.contour(RADIAL_SAMPLES)
            val raw = FloatArray(RADIAL_SAMPLES) { k -> kotlin.math.hypot(c[k][0], c[k][1]) * (1f + f(-0.06f, 0.06f)) }
            val radial = FloatArray(RADIAL_SAMPLES) { k -> (raw[(k + RADIAL_SAMPLES - 1) % RADIAL_SAMPLES] + 2 * raw[k] + raw[(k + 1) % RADIAL_SAMPLES]) / 4f }
            val rimW = if (rimAcetate) f(0.34f, 0.62f) else f(0.15f, 0.22f)
            val boost = if (rimAcetate && rnd.nextBoolean()) FramePhotoAnalyzer.safeTopBoost(base.height, rimW, f(0.3f, 1.0f)) else 0f
            val shape = base.copy(radial = radial, topBoost = boost)
            val spec = FrameSpec(
                id = "r$i", name = "r$i", category = Category.EYEGLASSES, shape = shape,
                rim = rim, frameColor = argb(60, 60, 60), accentColor = argb(200, 200, 200), tint = LensTint(argb(190, 215, 225), 0.07f),
                rimWidth = rimW, bridgeWidth = f(1.5f, 2.2f), doubleBridge = rnd.nextBoolean(), rimMode = RimMode.values()[rnd.nextInt(3)],
                templeHeight = f(0.15f, 1.0f), templeThickness = f(0.2f, 0.4f), templeTaper = f(0.45f, 1.3f), templeColor = argb(120, 120, 126), tipColor = argb(20, 20, 24), tipStart = f(0.5f, 0.85f)
            )
            val g = EyewearGeometry.build(spec)
            for (mesh in listOf(g.frame, g.lenses)) for (v in 0 until mesh.vertexCount) {
                val p = mesh.position(v)
                assertTrue("$i non-finite vertex", p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
                assertTrue("$i vertex out of range $p", abs(p.x) < 9.2f && p.y > -1.4f && p.y < 6.2f)
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

    // ---- half-rim and rimless --------------------------------------------------------------------------------

    /** Rimless: clear lenses (almost invisible), a metal bridge piece and two end pieces. Half-rim: rim only on the upper half. */
    private fun renderOpen(half: Boolean, pxPerMm: Float = 6f, apW: Float = 52f, apH: Float = 36f, rim: Float = 4f, bridge: Float = 18f, metal: Int = argb(150, 150, 156), bg: Int = WHITE): Img {
        val frameMm = 2 * (apW + 2 * rim) + bridge
        val margin = 70
        val w = (frameMm * pxPerMm).toInt() + 2 * margin
        val h = ((apH + 2 * rim) * pxPerMm).toInt() + 2 * margin
        val px = IntArray(w * h) { bg }
        val cy = h / 2f
        for (y in 0 until h) for (x in 0 until w) {
            for (s in intArrayOf(-1, 1)) {
                val cx = w / 2f + s * (bridge / 2 + rim + apW / 2) * pxPerMm
                val u = s * (x - cx) / pxPerMm
                val v = -(y - cy) / pxPerMm
                fun inside(a: Float, b: Float) = abs(u / a).pow(3.2f) + abs(v / b).pow(3.2f) <= 1f
                if (half && inside(apW / 2 + rim, apH / 2 + rim) && !inside(apW / 2, apH / 2) && v >= -0.1f * apH / 2) px[y * w + x] = metal
            }
            val topY = cy - apH * 0.30f * pxPerMm
            if (abs(x - w / 2f) <= bridge / 2 * pxPerMm + 4 && abs(y - topY) <= 2f * pxPerMm) px[y * w + x] = metal       // bridge piece
            for (s in intArrayOf(-1, 1)) {                                                                              // end pieces
                val ex = w / 2f + s * (bridge / 2 + 2 * rim + apW + 1f) * pxPerMm
                if (abs(x - ex) <= 3f * pxPerMm && abs(y - topY) <= 2.5f * pxPerMm) px[y * w + x] = metal
            }
        }
        return Img(w, h, px, BooleanArray(w * h))
    }

    private fun analyzeOpen(img: Img, mode: RimMode, preset: LensPreset = LensPreset.RECTANGLE, hints: PhotoHints = PhotoHints.parse("52-18-140"), choice: PatternChoice = PatternChoice.AUTO, cat: Category = Category.EYEGLASSES) =
        FramePhotoAnalyzer.analyzeOpen(img.px, img.w, img.h, "my-open", "Open", cat, hints, choice, mode, preset)

    @Test fun rimlessFrameBecomesAThreePieceFrameWithTheChosenLensShape() {
        val spec = ok(analyzeOpen(renderOpen(half = false), RimMode.NONE, LensPreset.OVAL))
        assertEquals(RimMode.NONE, spec.rimMode)
        assertEquals(RimStyle.WIRE, spec.rim)
        assertTrue("width ${spec.shape.width}", spec.shape.width in 5.2f..5.55f)
        assertTrue("oval is wider than tall: ${spec.shape.width} x ${spec.shape.height}", spec.shape.height < spec.shape.width * 0.8f)
        assertTrue("colour ${chan(spec.frameColor).toList()}", near(spec.frameColor, 150, 150, 156, 30))
        assertTrue(spec.shape.radial == null)
    }

    @Test fun halfRimKeepsTheMeasuredRimThickness() {
        val spec = ok(analyzeOpen(renderOpen(half = true, metal = argb(20, 20, 24)), RimMode.HALF, LensPreset.RECTANGLE))
        assertEquals(RimMode.HALF, spec.rimMode)
        assertEquals(RimStyle.ACETATE, spec.rim)
        assertTrue("rim ${spec.rimWidth}", spec.rimWidth in 0.34f..0.46f)
        assertTrue("colour ${chan(spec.frameColor).toList()}", near(spec.frameColor, 20, 20, 24, 30))
    }

    @Test fun openFramesRejectCroppedAndEmptyPhotos() {
        assertTrue(FramePhotoAnalyzer.analyzeOpen(IntArray(300 * 300) { WHITE }, 300, 300, "x", "x", Category.EYEGLASSES, PhotoHints(), PatternChoice.AUTO, RimMode.NONE, LensPreset.ROUND) is PhotoResult.Fail)
        val cropped = renderOpen(half = true)
        for (y in 0 until cropped.h) for (x in 0 until 30) cropped.px[y * cropped.w + x] = argb(20, 20, 24) // frame running off the edge
        assertTrue(analyzeOpen(cropped, RimMode.HALF) is PhotoResult.Fail)
    }

    @Test fun everyLensPresetGivesAValidFrameInEveryMode() {
        for (preset in LensPreset.values()) for (mode in listOf(RimMode.HALF, RimMode.NONE)) for (size in listOf("46-18", "52-18", "58-16")) {
            val spec = ok(analyzeOpen(renderOpen(half = mode == RimMode.HALF), mode, preset, PhotoHints.parse(size)))
            val g = EyewearGeometry.build(spec)
            for (v in 0 until g.frame.vertexCount) { val p = g.frame.position(v); assertTrue("$preset/$mode/$size vertex $p", p.x.isFinite() && abs(p.x) < 8.9f && p.y > -1.2f && p.y < 5.95f) }
        }
    }

    @Test fun rimModeSurvivesSavingAndLoading() {
        for (mode in RimMode.values()) {
            val spec = FrameCatalog.all.first().copy(id = "m-$mode", rimMode = mode)
            assertEquals(spec, FrameCodec.decode(FrameCodec.encode(spec)))
        }
        val parts = FrameCodec.encode(FrameCatalog.all.first()).split("|").toMutableList()
        parts[0] = "F2"; parts[11] = parts[11].split(",").take(7).joinToString(",") // what an older version wrote: no rim mode, no temple fields
        val old = FrameCodec.decode(parts.joinToString("|"))!!
        assertEquals(RimMode.FULL, old.rimMode); assertEquals(0, old.templeColor); assertEquals(0, old.tipColor)
    }

    // ---- separate temple photo ---------------------------------------------------------------------------------

    /** A temple laid flat, hinge on the left: front section [front], then a differently coloured tip from [tipFrom]. */
    private fun renderTemple(vertical: Boolean = false, lenMm: Float = 140f, hingeH: Float = 8f, tipH: Float = 5f, front: Int = BLACK, tip: Int = BLACK, tipFrom: Float = 0.7f, margin: Int = 60): Img {
        val ppm = 6f
        val a = (lenMm * ppm).toInt(); val c = (maxOf(hingeH, tipH) * ppm).toInt() + 2 * 20
        val w = if (vertical) c + 2 * margin else a + 2 * margin
        val h = if (vertical) a + 2 * margin else c + 2 * margin
        val px = IntArray(w * h) { WHITE }
        for (i in 0 until a) {
            val t = i / a.toFloat()
            val s = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val half = (hingeH + (tipH - hingeH) * (s * s * (3f - 2f * s))) * ppm / 2f
            val colour = if (t >= tipFrom) tip else front
            for (j in 0 until c) if (abs(j - c / 2f) <= half) {
                if (vertical) px[(margin + i) * w + margin + j] = colour else px[(margin + j) * w + margin + i] = colour
            }
        }
        return Img(w, h, px, BooleanArray(w * h))
    }

    private fun temple(img: Img, hints: PhotoHints = PhotoHints.parse("52-18-140")) = FramePhotoAnalyzer.analyzeTemple(img.px, img.w, img.h, hints)
    private fun okT(r: TempleResult): TempleLook { assertTrue("expected Ok but was ${(r as? TempleResult.Fail)?.reason}", r is TempleResult.Ok); return (r as TempleResult.Ok).look }

    @Test fun metalFrontWithRubberTipIsReadAsTwoParts() {
        val look = okT(temple(renderTemple(hingeH = 8f, tipH = 7f, front = argb(150, 150, 156), tip = argb(20, 20, 24))))
        assertTrue("height ${look.heightCm}", look.heightCm in 0.7f..0.9f)
        assertTrue("front colour ${chan(look.color).toList()}", near(look.color, 150, 150, 156, 25))
        assertTrue(look.metallic)
        assertTrue("tip colour ${chan(look.tipColor).toList()}", look.tipColor != 0 && near(look.tipColor, 20, 20, 24, 30))
        assertTrue("tip starts at ${look.tipStart}", look.tipStart in 0.6f..0.8f)
        assertTrue("taper ${look.taper}", look.taper in 0.75f..1.0f)
    }

    @Test fun plainPlasticTempleHasNoSeparateTipAndTapers() {
        val look = okT(temple(renderTemple(hingeH = 9f, tipH = 6f)))
        assertEquals(0, look.tipColor)
        assertTrue(!look.metallic)
        assertTrue("height ${look.heightCm}", look.heightCm in 0.8f..1.0f)
        assertTrue("thickness ${look.thicknessCm}", look.thicknessCm in 0.22f..0.38f)
        assertTrue("taper ${look.taper}", look.taper in 0.55f..0.8f)
    }

    @Test fun thinGoldWireTempleIsDetected() {
        val look = okT(temple(renderTemple(hingeH = 2f, tipH = 2f, front = argb(214, 178, 94), tip = argb(214, 178, 94))))
        assertTrue("height ${look.heightCm}", look.heightCm in 0.15f..0.3f)
        assertTrue(look.metallic)
        assertEquals(0, look.tipColor)
    }

    @Test fun verticalTemplePhotoWorksToo() {
        val look = okT(temple(renderTemple(vertical = true, hingeH = 8f, tipH = 5f, front = argb(150, 150, 156), tip = argb(20, 20, 24))))
        assertTrue("height ${look.heightCm}", look.heightCm in 0.7f..0.9f)
        assertTrue(look.tipColor != 0)
    }

    @Test fun badTemplePhotosAreRejected() {
        val blob = Img(300, 300, IntArray(300 * 300) { x -> if ((x % 300) in 100..200 && (x / 300) in 100..200) BLACK else WHITE }, BooleanArray(300 * 300))
        val r1 = temple(blob); assertTrue(r1 is TempleResult.Fail && r1.reason.contains("seedhi"))
        val r2 = temple(renderTemple(margin = 0)); assertTrue(r2 is TempleResult.Fail && r2.reason.contains("kati"))
        assertTrue(temple(Img(300, 300, IntArray(300 * 300) { WHITE }, BooleanArray(300 * 300))) is TempleResult.Fail)
    }

    @Test fun measuredTempleIsAppliedAndStaysSafeIn3D() {
        val front = ok(analyze(render()))
        val look = okT(temple(renderTemple(hingeH = 8f, tipH = 7f, front = argb(150, 150, 156), tip = argb(20, 20, 24))))
        val spec = FramePhotoAnalyzer.withTemple(front, look)
        assertEquals(look.color, spec.templeColor); assertEquals(look.tipColor, spec.tipColor); assertTrue(spec.templeMetallic)
        assertEquals(spec, FrameCodec.decode(FrameCodec.encode(spec)))
        val g = EyewearGeometry.build(spec)
        for (v in 0 until g.frame.vertexCount) {
            if (g.frame.vertices[v * MeshData.STRIDE_FLOATS + 6] != EyewearGeometry.PART_TEMPLE) continue
            val p = g.frame.position(v)
            assertTrue("arm inside head at $p", !Occluders.insideHead(p.x, p.y, p.z))
            assertTrue("arm inside ear at $p", !Occluders.insideEar(p.x, p.y, p.z, if (p.x > 0) 1f else -1f, 0.9f))
        }
        assertEquals(140f, PhotoHints.parse("52-18-140").templeMm!!, 0f)
        assertEquals(null, PhotoHints.parse("52-18").templeMm)
    }
}
