package com.optitry.app.eyewear

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Optional numbers printed on the frame ("52-18-140"): lens width and bridge in mm. They calibrate the photo's scale. */
class PhotoHints(val lensMm: Float? = null, val bridgeMm: Float? = null) {
    companion object {
        /** Parses "52-18-140", "52 18", "52/18/140" ... Returns empty hints if nothing sensible is found. */
        fun parse(text: String): PhotoHints {
            val nums = Regex("\\d{2,3}").findAll(text).map { it.value.toInt() }.toList()
            val lens = nums.getOrNull(0)?.takeIf { it in 40..64 }?.toFloat()
            val bridge = nums.getOrNull(1)?.takeIf { it in 10..26 }?.toFloat()
            return if (lens == null) PhotoHints() else PhotoHints(lens, bridge)
        }
    }
}

enum class PatternChoice { AUTO, SOLID, TORTOISE, CRYSTAL, TWO_TONE }

sealed class PhotoResult {
    class Ok(val spec: FrameSpec, val summary: String) : PhotoResult()
    class Fail(val reason: String) : PhotoResult()
}

/**
 * Turns a front-on photo of a frame (plain background) into a [FrameSpec] the 3D engine renders: it measures the lens
 * outline, rim thickness and bridge, and reads the colours, so the shop owner never has to model anything by hand.
 * Every number is clamped to the ranges the geometry is tested for, so a user frame can never produce broken 3D.
 */
object FramePhotoAnalyzer {
    private const val FG_DIST = 70f           // colour distance from the background that counts as "frame"
    private const val DEFAULT_FRONT_MM = 135f // typical frame front width when no size is typed in

    private class Comp {
        var area = 0; var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        var sx = 0L; var sy = 0L
        val cx get() = if (area == 0) 0f else sx.toFloat() / area
        val cy get() = if (area == 0) 0f else sy.toFloat() / area
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
    }

    fun analyze(
        px: IntArray, w: Int, h: Int, id: String, name: String, category: Category,
        hints: PhotoHints = PhotoHints(), choice: PatternChoice = PatternChoice.AUTO
    ): PhotoResult {
        if (w < 64 || h < 64 || px.size < w * h) return PhotoResult.Fail("Photo bahut chhoti hai. Badi photo lo.")
        val bg = borderColour(px, w, h)
        val mask = BooleanArray(w * h) { colourDist(px[it], bg) > FG_DIST }

        // keep real parts of the frame, drop specks and dust
        val labels = IntArray(w * h)
        val comps = label(mask, true, w, h, labels)
        val minArea = max(30, (w * h * 0.002f).toInt())
        val keep = BooleanArray(comps.size + 1) { it > 0 && comps[it - 1].area >= minArea }
        for (i in mask.indices) if (mask[i] && !keep[labels[i]]) mask[i] = false
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for ((k, c) in comps.withIndex()) if (keep[k + 1]) { x0 = min(x0, c.x0); y0 = min(y0, c.y0); x1 = max(x1, c.x1); y1 = max(y1, c.y1) }
        if (x1 < 0) return PhotoResult.Fail("Photo mein frame nahi mila. Saaf, plain background par photo lo.")
        if (x0 <= 1 || y0 <= 1 || x1 >= w - 2 || y1 >= h - 2)
            return PhotoResult.Fail("Frame photo ke kinare se kata hua hai. Poora frame dikhe, charon taraf thodi jagah chhodo.")
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        if (bw < w * 0.30f) return PhotoResult.Fail("Frame photo mein bahut chhota hai. Thoda paas se ya zoom karke photo lo.")
        if (bw < bh * 1.4f) return PhotoResult.Fail("Frame seedha saamne se nahi dikh raha. Frame ko camera ke saamne seedha rakho.")
        val cxMid = (x0 + x1) / 2f

        // lens openings = background-coloured regions fully enclosed by the frame
        val outside = BooleanArray(w * h)
        floodOutside(mask, outside, w, h)
        val holeMask = BooleanArray(w * h) { !mask[it] && !outside[it] }
        val holeLabels = IntArray(w * h)
        val holes = label(holeMask, true, w, h, holeLabels)
        val minHole = bw * bh * 0.012f
        var leftIdx = -1; var rightIdx = -1
        for ((k, c) in holes.withIndex()) {
            if (c.area < minHole || c.cx < x0 || c.cx > x1) continue
            if (c.cx < cxMid) { if (leftIdx < 0 || c.area > holes[leftIdx].area) leftIdx = k }
            else if (rightIdx < 0 || c.area > holes[rightIdx].area) rightIdx = k
        }
        val solidLens = leftIdx < 0 || rightIdx < 0

        val apW: Float; val apH: Float; val fill: Float; val taperRaw: Float
        var liftPx = 0f
        var rimPx: Float; var topT = 0f
        val gapPx: Float
        val colourSamples = ArrayList<Sample>()
        var lensTintColour = 0

        if (!solidLens) {
            val l = holes[leftIdx]; val r = holes[rightIdx]
            val lLab = leftIdx + 1; val rLab = rightIdx + 1
            apW = (l.w + r.w) / 2f; apH = (l.h + r.h) / 2f
            fill = (l.area.toFloat() / (l.w * l.h) + r.area.toFloat() / (r.w * r.h)) / 2f
            taperRaw = (taperOf(holeLabels, w, rLab, r) + taperOf(holeLabels, w, lLab, l, mirror = true)) / 2f
            liftPx = liftOf(holeLabels, w, rLab, r)
            gapPx = (r.x0 - (l.x1 + 1)).toFloat()
            val side = ArrayList<Float>(); val up = ArrayList<Float>()
            fun thick(lab: Int, c: Comp, dx: Int, dy: Int): Float {
                var x = c.cx.roundToInt(); var y = c.cy.roundToInt()
                if (dx != 0) y = (c.y0 + c.y1) / 2 else x = (c.x0 + c.x1) / 2
                while (x in 0 until w && y in 0 until h && holeLabels[y * w + x] == lab) { x += dx; y += dy }
                var n = 0
                while (x in 0 until w && y in 0 until h && mask[y * w + x]) { n++; x += dx; y += dy }
                return n.toFloat()
            }
            side += thick(rLab, r, 1, 0); side += thick(lLab, l, -1, 0)
            side += thick(rLab, r, 0, 1); side += thick(lLab, l, 0, 1)
            up += thick(rLab, r, 0, -1); up += thick(lLab, l, 0, -1)
            rimPx = max(2f, median(side)); topT = median(up)
            collectRimColours(px, mask, holeLabels, w, h, rLab, r, rimPx, colourSamples)
            collectRimColours(px, mask, holeLabels, w, h, lLab, l, rimPx, colourSamples)
        } else {
            // dark / tinted lenses fill the rim: treat each half of the frame as one blob
            var bx = (cxMid).roundToInt(); var best = Int.MAX_VALUE
            for (x in (cxMid - bw * 0.08f).roundToInt()..(cxMid + bw * 0.08f).roundToInt()) {
                var cnt = 0; for (y in y0..y1) if (mask[y * w + x]) cnt++
                if (cnt < best) { best = cnt; bx = x }
            }
            val lc = Comp(); val rc = Comp()
            for (y in y0..y1) for (x in x0..x1) if (mask[y * w + x]) {
                val c = if (x < bx) lc else rc
                c.area++; c.sx += x; c.sy += y
                if (x < c.x0) c.x0 = x; if (x > c.x1) c.x1 = x; if (y < c.y0) c.y0 = y; if (y > c.y1) c.y1 = y
            }
            if (lc.area == 0 || rc.area == 0) return PhotoResult.Fail("Frame ki shape samajh nahi aayi. Saaf photo lo.")
            val est = max(2f, (lc.w + rc.w) / 2f * 0.06f)
            rimPx = est
            apW = max(4f, (lc.w + rc.w) / 2f - 2 * est); apH = max(4f, (lc.h + rc.h) / 2f - 2 * est)
            fill = (lc.area.toFloat() / (lc.w * lc.h) + rc.area.toFloat() / (rc.w * rc.h)) / 2f
            taperRaw = 0f
            gapPx = max(0f, (rc.x0 - (lc.x1 + 1)).toFloat()) + 2 * est
            val lensSamples = ArrayList<Int>()
            for (c in listOf(lc, rc)) {
                val cx = c.cx.roundToInt(); val cy = c.cy.roundToInt()
                val dx = max(1, (c.w * 0.18f).toInt()); val dy = max(1, (c.h * 0.18f).toInt())
                for (yy in cy - dy..cy + dy step max(1, dy / 3)) for (xx in cx - dx..cx + dx step max(1, dx / 3)) lensSamples += px[yy * w + xx]
            }
            lensTintColour = medianColour(lensSamples)
            // rim colour: pixels just inside the outline, sampled along rays from each blob centre
            for (c in listOf(lc, rc)) rayRimColours(px, mask, w, h, c.cx.roundToInt(), c.cy.roundToInt(), est, colourSamples)
        }

        // ---- scale: px per mm ----
        var pxPerMm = bw / DEFAULT_FRONT_MM
        if (hints.lensMm != null) {
            val bridge = hints.bridgeMm ?: 18f
            repeat(3) {
                val rimMm = rimPx / pxPerMm
                pxPerMm = bw / (2 * hints.lensMm + bridge + 4 * rimMm)
            }
        }
        val rimMm = rimPx / pxPerMm
        val apWmm = apW / pxPerMm; val apHmm = apH / pxPerMm

        // ---- lens outline ----
        val widthCm = ((apWmm + rimMm) / 10f).coerceIn(4.7f, 5.8f)
        val heightCm = ((apHmm + rimMm) / 10f).coerceIn(3.6f, 5.1f)
        val squareness = solveSquareness(fill).coerceIn(2.0f, 4.2f)
        val taper = taperRaw.coerceIn(-0.13f, 0.10f).let { if (abs(it) < 0.02f) 0f else it }
        val liftCm = (liftPx / pxPerMm / 10f) / 0.4f // outer corner rises by lift*(outer share^2); empirical factor for the narrower outer band
        val lift = if (liftCm < 0.08f) 0f else liftCm.coerceIn(0f, 0.5f)
        val rimIsAcetate = rimMm >= 2.8f
        val rimStyle = if (rimIsAcetate) RimStyle.ACETATE else RimStyle.WIRE
        val rimCm = if (rimIsAcetate) (rimMm / 10f).coerceIn(0.34f, 0.62f) else (rimMm / 10f).coerceIn(0.15f, 0.22f)
        val topBoost = if (!solidLens && rimIsAcetate && rimPx > 0f) ((topT / rimPx) - 1f).let { if (it < 0.3f) 0f else it.coerceIn(0f, 1.0f) } else 0f
        val bridgeCm = ((gapPx / pxPerMm - rimMm) / 10f).coerceIn(1.7f, 2.1f)

        // ---- colour & pattern ----
        val look = decideLook(colourSamples, choice, rimStyle)
        val tint = when {
            category == Category.EYEGLASSES -> LensTint(rgb(190, 215, 225), 0.07f)
            solidLens && lensTintColour != 0 -> LensTint(lensTintColour or (0xFF shl 24), (0.5f + 0.4f * (1f - lum(lensTintColour) / 255f)).coerceIn(0.55f, 0.85f))
            else -> LensTint(rgb(24, 28, 34), 0.78f)
        }

        val spec = FrameSpec(
            id = id, name = name, category = category,
            shape = LensShape(widthCm, heightCm, squareness, taper = taper, lift = lift, topBoost = topBoost),
            rim = rimStyle, frameColor = look.base, accentColor = look.accent, pattern = look.pattern,
            metallic = rimStyle == RimStyle.WIRE, tint = tint, rimWidth = rimCm, bridgeWidth = bridgeCm
        )
        val summary = "Lens %.0f x %.0f mm, bridge %.0f mm, rim %.1f mm (%s), %s".format(
            apWmm, apHmm, (gapPx / pxPerMm - rimMm).coerceAtLeast(0f), rimMm,
            if (rimIsAcetate) "plastic" else "metal", look.pattern.name.lowercase()
        )
        return PhotoResult.Ok(spec, summary)
    }

    // ------------------------------------------------------------------------------------------------ geometry

    /** Super-ellipse fill ratio f(n) = integral_0^1 (1 - x^n)^(1/n) dx (area / bounding box); inverted by bisection. */
    internal fun solveSquareness(fillRatio: Float): Float {
        fun f(n: Double): Double {
            val steps = 400; var s = 0.0
            for (i in 0 until steps) { val x = (i + 0.5) / steps; s += (1.0 - x.pow(n)).pow(1.0 / n) }
            return s / steps
        }
        var lo = 1.8; var hi = 6.0
        if (fillRatio <= f(lo)) return lo.toFloat()
        if (fillRatio >= f(hi)) return hi.toFloat()
        repeat(30) { val mid = (lo + hi) / 2; if (f(mid) < fillRatio) lo = mid else hi = mid }
        return ((lo + hi) / 2).toFloat()
    }

    private fun rowWidth(labels: IntArray, w: Int, lab: Int, c: Comp, y: Int): Int {
        var n = 0
        for (x in c.x0..c.x1) if (labels[y * w + x] == lab) n++
        return n
    }

    /** Model taper: x scales by 1 + taper * (y / b); measured from the lens width a quarter of the way down vs up. */
    private fun taperOf(labels: IntArray, w: Int, lab: Int, c: Comp, mirror: Boolean = false): Float {
        fun avg(yc: Int): Float { var s = 0f; var n = 0; for (y in yc - 2..yc + 2) if (y in c.y0..c.y1) { s += rowWidth(labels, w, lab, c, y); n++ }; return if (n == 0) 0f else s / n }
        val wt = avg(c.y0 + (c.h * 0.25f).toInt()); val wb = avg(c.y0 + (c.h * 0.75f).toInt())
        if (wt <= 0f || wb <= 0f) return 0f
        val r = wt / wb
        return 2f * (r - 1f) / (r + 1f)
    }

    /** Pixels by which the outer part of the opening sits higher than the inner part (cat-eye lift); outer = image right. */
    private fun liftOf(labels: IntArray, w: Int, lab: Int, c: Comp): Float {
        var so = 0f; var no = 0; var si = 0f; var ni = 0
        val outerFrom = c.x0 + c.w * 0.6f; val innerTo = c.x0 + c.w * 0.4f
        for (y in c.y0..c.y1) for (x in c.x0..c.x1) if (labels[y * w + x] == lab) {
            if (x >= outerFrom) { so += y; no++ }
            if (x <= innerTo) { si += y; ni++ }
        }
        if (no == 0 || ni == 0) return 0f
        return si / ni - so / no
    }

    // ------------------------------------------------------------------------------------------------ colour

    private class Sample(val colour: Int, val top: Boolean, val bottom: Boolean)

    private class Look(val base: Int, val accent: Int, val pattern: Pattern)

    private fun collectRimColours(px: IntArray, mask: BooleanArray, labels: IntArray, w: Int, h: Int, lab: Int, c: Comp, rimPx: Float, out: MutableList<Sample>) {
        val cx = c.cx; val cy = c.cy
        for (k in 0 until 36) {
            val a = 2.0 * PI * k / 36
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            var t = 0f
            while (true) {
                val x = (cx + dx * t).roundToInt(); val y = (cy + dy * t).roundToInt()
                if (x !in 0 until w || y !in 0 until h) break
                if (labels[y * w + x] != lab) break
                t += 1f
            }
            for (f in floatArrayOf(0.35f, 0.55f, 0.75f)) {
                val tt = t + rimPx * f
                val x = (cx + dx * tt).roundToInt(); val y = (cy + dy * tt).roundToInt()
                if (x in 0 until w && y in 0 until h && mask[y * w + x]) out += Sample(px[y * w + x], dy < -0.5f, dy > 0.5f)
            }
        }
    }

    private fun rayRimColours(px: IntArray, mask: BooleanArray, w: Int, h: Int, cx: Int, cy: Int, rimPx: Float, out: MutableList<Sample>) {
        for (k in 0 until 36) {
            val a = 2.0 * PI * k / 36
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            var t = 0f; var lastIn = 0f
            while (true) {
                val x = (cx + dx * t).roundToInt(); val y = (cy + dy * t).roundToInt()
                if (x !in 0 until w || y !in 0 until h) break
                if (mask[y * w + x]) lastIn = t else if (t - lastIn > 3f) break
                t += 1f
            }
            for (f in floatArrayOf(0.4f, 0.7f)) {
                val tt = lastIn - rimPx * f
                val x = (cx + dx * tt).roundToInt(); val y = (cy + dy * tt).roundToInt()
                if (tt > 0 && x in 0 until w && y in 0 until h && mask[y * w + x]) out += Sample(px[y * w + x], dy < -0.5f, dy > 0.5f)
            }
        }
    }

    private fun decideLook(samples: List<Sample>, choice: PatternChoice, rim: RimStyle): Look {
        if (samples.isEmpty()) return Look(rgb(24, 24, 28), rgb(24, 24, 28), Pattern.SOLID)
        val all = samples.map { it.colour }
        val med = medianColour(all)
        val lums = all.map { lum(it) }.sorted()
        val mean = lums.average().toFloat()
        val std = sqrt(lums.map { (it - mean) * (it - mean) }.average()).toFloat()
        val dark = all.sortedBy { lum(it) }[(all.size * 0.2f).toInt()]
        val light = all.sortedBy { lum(it) }[(all.size * 0.8f).toInt()]
        val hsv = hsv(med)
        val brownish = hsv[0] in 8f..55f && hsv[1] >= 0.22f && hsv[2] in 0.12f..0.88f
        val top = samples.filter { it.top }.map { it.colour }
        val bottom = samples.filter { it.bottom }.map { it.colour }
        val topMed = if (top.isEmpty()) med else medianColour(top)
        val botMed = if (bottom.isEmpty()) med else medianColour(bottom)
        val twoToneDist = colourDist(topMed, botMed)
        val tortoiseAuto = rim == RimStyle.ACETATE && brownish && std >= 18f
        val twoToneAuto = !tortoiseAuto && twoToneDist > 90f

        fun tortoise(): Look {
            val base = if (colourDist(dark, light) > 30f) dark else mix(med, rgb(40, 22, 10), 0.5f)
            val acc = if (colourDist(dark, light) > 30f) light else mix(med, rgb(235, 175, 95), 0.6f)
            return Look(base, acc, Pattern.TORTOISE)
        }
        return when (choice) {
            PatternChoice.SOLID -> Look(med, med, Pattern.SOLID)
            PatternChoice.TORTOISE -> tortoise()
            PatternChoice.CRYSTAL -> Look(mix(med, rgb(255, 255, 255), 0.35f), mix(med, rgb(255, 255, 255), 0.35f), Pattern.CRYSTAL)
            PatternChoice.TWO_TONE -> Look(topMed, botMed, Pattern.GRADIENT)
            PatternChoice.AUTO -> when {
                tortoiseAuto -> tortoise()
                twoToneAuto -> Look(topMed, botMed, Pattern.GRADIENT)
                else -> Look(med, med, Pattern.SOLID)
            }
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun lum(c: Int) = 0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)
    private fun colourDist(a: Int, b: Int): Float {
        val dr = r(a) - r(b); val dg = g(a) - g(b); val db = b(a) - b(b)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
    private fun mix(a: Int, b: Int, t: Float) =
        rgb((r(a) + (r(b) - r(a)) * t).roundToInt(), (g(a) + (g(b) - g(a)) * t).roundToInt(), (b(a) + (b(b) - b(a)) * t).roundToInt())

    private fun medianColour(cols: List<Int>): Int {
        if (cols.isEmpty()) return 0
        fun med(f: (Int) -> Int): Int { val s = cols.map(f).sorted(); return s[s.size / 2] }
        return rgb(med(::r), med(::g), med(::b))
    }
    private fun median(v: List<Float>): Float { if (v.isEmpty()) return 0f; val s = v.sorted(); return s[s.size / 2] }

    private fun hsv(c: Int): FloatArray {
        val rf = r(c) / 255f; val gf = g(c) / 255f; val bf = b(c) / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf)); val d = mx - mn
        val hue = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * ((bf - rf) / d + 2f)
            else -> 60f * ((rf - gf) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(hue, if (mx == 0f) 0f else d / mx, mx)
    }

    // ------------------------------------------------------------------------------------------------ pixels

    private fun borderColour(px: IntArray, w: Int, h: Int): Int {
        val list = ArrayList<Int>()
        for (t in 0 until 3) {
            for (x in 0 until w) { list += px[t * w + x]; list += px[(h - 1 - t) * w + x] }
            for (y in 0 until h) { list += px[y * w + t]; list += px[y * w + (w - 1 - t)] }
        }
        return medianColour(list)
    }

    private fun label(mask: BooleanArray, target: Boolean, w: Int, h: Int, labels: IntArray): List<Comp> {
        val comps = ArrayList<Comp>()
        val stack = IntArray(w * h)
        java.util.Arrays.fill(labels, 0)
        for (start in 0 until w * h) {
            if (mask[start] != target || labels[start] != 0) continue
            val id = comps.size + 1
            val c = Comp(); comps += c
            var sp = 0; stack[sp++] = start; labels[start] = id
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                c.area++; c.sx += x; c.sy += y
                if (x < c.x0) c.x0 = x; if (x > c.x1) c.x1 = x; if (y < c.y0) c.y0 = y; if (y > c.y1) c.y1 = y
                if (x > 0 && mask[p - 1] == target && labels[p - 1] == 0) { labels[p - 1] = id; stack[sp++] = p - 1 }
                if (x < w - 1 && mask[p + 1] == target && labels[p + 1] == 0) { labels[p + 1] = id; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - w] == target && labels[p - w] == 0) { labels[p - w] = id; stack[sp++] = p - w }
                if (y < h - 1 && mask[p + w] == target && labels[p + w] == 0) { labels[p + w] = id; stack[sp++] = p + w }
            }
        }
        return comps
    }

    private fun floodOutside(mask: BooleanArray, outside: BooleanArray, w: Int, h: Int) {
        val stack = IntArray(w * h)
        var sp = 0
        fun seed(i: Int) { if (!mask[i] && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val p = stack[--sp]; val x = p % w; val y = p / w
            if (x > 0) seed(p - 1); if (x < w - 1) seed(p + 1); if (y > 0) seed(p - w); if (y < h - 1) seed(p + w)
        }
    }
}

/** One frame <-> one text line, so user frames can be saved in a plain file and read back. */
object FrameCodec {
    private const val V = "F1"
    fun encode(s: FrameSpec): String {
        val t = s.shape; val n = s.tint
        val name = s.name.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
        return listOf(
            V, s.id, name, s.category.name,
            listOf(t.width, t.height, t.squareness, t.taper, t.lift, t.topBoost, t.noseCut, t.facets, t.corner).joinToString(","),
            s.rim.name, s.frameColor, s.accentColor, s.pattern.name, s.metallic,
            listOf(n.color, n.opacity, n.mirror, n.gradient).joinToString(","),
            listOf(s.rimWidth, s.rimDepth, s.bridgeWidth, s.wrap, s.templeThickness, s.templeHeight).joinToString(",")
        ).joinToString("|")
    }

    fun decode(line: String): FrameSpec? = try {
        val p = line.split("|")
        if (p.size != 12 || p[0] != V) null else {
            val sh = p[4].split(",")
            val ti = p[10].split(",")
            val ex = p[11].split(",")
            FrameSpec(
                id = p[1], name = p[2], category = Category.valueOf(p[3]),
                shape = LensShape(sh[0].toFloat(), sh[1].toFloat(), sh[2].toFloat(), sh[3].toFloat(), sh[4].toFloat(), sh[5].toFloat(), sh[6].toFloat(), sh[7].toInt(), sh[8].toFloat()),
                rim = RimStyle.valueOf(p[5]), frameColor = p[6].toInt(), accentColor = p[7].toInt(),
                pattern = Pattern.valueOf(p[8]), metallic = p[9].toBoolean(),
                tint = LensTint(ti[0].toInt(), ti[1].toFloat(), ti[2].toFloat(), ti[3].toBoolean()),
                rimWidth = ex[0].toFloat(), rimDepth = ex[1].toFloat(), bridgeWidth = ex[2].toFloat(), wrap = ex[3].toFloat(),
                templeThickness = ex[4].toFloat(), templeHeight = ex[5].toFloat()
            )
        }
    } catch (e: Exception) { null }
}

/** The shop's own frames, kept in a small text file in the app's private storage. */
class MyFrameStore(private val file: File) {
    fun load(): List<FrameSpec> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { FrameCodec.decode(it) }

    fun add(spec: FrameSpec) { file.appendText(FrameCodec.encode(spec) + "\n") }

    fun remove(id: String) {
        if (!file.exists()) return
        file.writeText(load().filter { it.id != id }.joinToString("") { FrameCodec.encode(it) + "\n" })
    }
}
