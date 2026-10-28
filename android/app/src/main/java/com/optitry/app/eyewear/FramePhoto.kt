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

        // per-lens outline in image space: radius (px) from the box centre at RADIAL_SAMPLES angles (0 = image right, counter-clockwise)
        val profR: FloatArray; val profL: FloatArray
        val centreR: FloatArray; val centreL: FloatArray
        val taperRaw: Float
        var liftPx = 0f
        val rimPx: Float; var topT = 0f
        val gapPx: Float
        var doubleBridge = false
        val colourSamples = ArrayList<Sample>()
        var lensTintColour = 0

        if (!solidLens) {
            val l = holes[leftIdx]; val r = holes[rightIdx]
            val lLab = leftIdx + 1; val rLab = rightIdx + 1
            val hullR = hullOf(boundary(holeLabels, w, h, rLab, r)); val hullL = hullOf(boundary(holeLabels, w, h, lLab, l))
            centreR = floatArrayOf((r.x0 + r.x1) / 2f, (r.y0 + r.y1) / 2f); centreL = floatArrayOf((l.x0 + l.x1) / 2f, (l.y0 + l.y1) / 2f)
            profR = radii(hullR, centreR); profL = radii(hullL, centreL)
            taperRaw = (taperOf(holeLabels, w, rLab, r) + taperOf(holeLabels, w, lLab, l, mirror = true)) / 2f
            liftPx = liftOf(holeLabels, w, rLab, r)
            gapPx = (r.x0 - (l.x1 + 1)).toFloat()
            // rim thickness from the top, bottom and nose side (the arms seen through the lens make the outer side unreliable)
            val topS = ArrayList<Float>(); val rimS = ArrayList<Float>()
            for ((c, lab, inner) in listOf(Triple(r, rLab, -1), Triple(l, lLab, 1))) {
                for (f in floatArrayOf(0.30f, 0.50f, 0.70f)) {
                    val xc = (c.x0 + c.w * f).toInt()
                    var yt = c.y0; while (yt <= c.y1 && holeLabels[yt * w + xc] != lab) yt++
                    var yb = c.y1; while (yb >= c.y0 && holeLabels[yb * w + xc] != lab) yb--
                    if (yt <= c.y1) topS += run(mask, w, h, xc, yt - 1, 0, -1)
                    if (yb >= c.y0) rimS += run(mask, w, h, xc, yb + 1, 0, 1)
                    val yr = (c.y0 + c.h * f).toInt()
                    var xi = if (inner < 0) c.x0 else c.x1
                    while (xi in c.x0..c.x1 && holeLabels[yr * w + xi] != lab) xi -= inner
                    if (xi in c.x0..c.x1) rimS += run(mask, w, h, xi + inner, yr, inner, 0)
                }
            }
            rimPx = max(2f, median(rimS.filter { it > 0f })); topT = median(topS.filter { it > 0f })
            // a small closed opening above the nose bridge = double-bridge (pilot) frame with a brow bar
            var centre = -1
            for ((k, c) in holes.withIndex()) {
                if (k == leftIdx || k == rightIdx) continue
                if (c.area >= bw * bh * 0.002f && abs(c.cx - cxMid) <= bw * 0.12f && c.cy < (l.cy + r.cy) / 2f && c.area < 0.4f * min(l.area, r.area))
                    if (centre < 0 || c.area > holes[centre].area) centre = k
            }
            doubleBridge = centre >= 0
            collectRimColours(px, mask, centreR, profR, rimPx, w, h, colourSamples)
            collectRimColours(px, mask, centreL, profL, rimPx, w, h, colourSamples)
        } else {
            // dark / tinted lenses fill the rim: treat each half of the frame as one blob
            var bx = cxMid.roundToInt(); var best = Int.MAX_VALUE
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
            centreR = floatArrayOf((rc.x0 + rc.x1) / 2f, (rc.y0 + rc.y1) / 2f); centreL = floatArrayOf((lc.x0 + lc.x1) / 2f, (lc.y0 + lc.y1) / 2f)
            fun blobHull(c: Comp, right: Boolean): Array<FloatArray> {
                val pts = ArrayList<IntArray>()
                for (y in c.y0..c.y1) for (x in c.x0..c.x1) {
                    if (!mask[y * w + x] || (x < bx) == right) continue
                    val edge = x == c.x0 || x == c.x1 || y == c.y0 || y == c.y1 ||
                        !mask[y * w + x - 1] || !mask[y * w + x + 1] || !mask[(y - 1) * w + x] || !mask[(y + 1) * w + x]
                    if (edge) pts += intArrayOf(x, y)
                }
                return hullOf(pts)
            }
            // the blob includes the rim: shrink every radius by the estimated rim thickness to get the lens opening
            profR = radii(blobHull(rc, true), centreR).map { max(it - est, it * 0.5f) }.toFloatArray()
            profL = radii(blobHull(lc, false), centreL).map { max(it - est, it * 0.5f) }.toFloatArray()
            taperRaw = 0f
            gapPx = max(0f, (rc.x0 - (lc.x1 + 1)).toFloat()) + 2 * est
            val lensSamples = ArrayList<Int>()
            for (c in listOf(lc, rc)) {
                val cx = c.cx.roundToInt(); val cy = c.cy.roundToInt()
                val dx = max(1, (c.w * 0.18f).toInt()); val dy = max(1, (c.h * 0.18f).toInt())
                for (yy in cy - dy..cy + dy step max(1, dy / 3)) for (xx in cx - dx..cx + dx step max(1, dx / 3)) lensSamples += px[yy * w + xx]
            }
            lensTintColour = medianColour(lensSamples)
            for (c in listOf(lc, rc)) rayRimColours(px, mask, w, h, c.cx.roundToInt(), c.cy.roundToInt(), est, colourSamples)
        }

        // ---- scale: px per mm. frame width = 2 lenses + bridge gap + 2 rim walls ----
        var pxPerMm = bw / DEFAULT_FRONT_MM
        if (hints.lensMm != null) {
            val bridge = hints.bridgeMm ?: 18f
            repeat(3) { pxPerMm = bw / (2 * hints.lensMm + bridge + 2 * (rimPx / pxPerMm)) }
        }
        val rimMm = rimPx / pxPerMm

        // ---- lens outline (model space: outer side +x, y up, cm). The left lens is the mirror image of the right one. ----
        val n = RADIAL_SAMPLES
        val outline = FloatArray(n)
        for (k in 0 until n) {
            val rr = profR[k]; val ll = profL[(n / 2 - k + n) % n]
            outline[k] = (rr + ll) / 2f
        }
        val smooth = FloatArray(n) { k -> (outline[(k + n - 2) % n] + 2 * outline[(k + n - 1) % n] + 3 * outline[k] + 2 * outline[(k + 1) % n] + outline[(k + 2) % n]) / 9f }
        var cm = FloatArray(n) { smooth[it] / pxPerMm / 10f }
        fun extent(f: (Float, Float) -> Float): Float {
            var lo = 1e9f; var hi = -1e9f
            for (k in 0 until n) { val t = 2.0 * PI * k / n; val v = f((cm[k] * cos(t)).toFloat(), (cm[k] * sin(t)).toFloat()); if (v < lo) lo = v; if (v > hi) hi = v }
            return hi - lo
        }
        var widthCm = extent { x, _ -> x }; var heightCm = extent { _, y -> y }
        // keep the size inside the range the 3D engine is tested for
        val fw = (widthCm.coerceIn(4.4f, 5.9f)) / widthCm
        val fh = (heightCm.coerceIn(3.4f, 5.3f)) / heightCm
        val f = if (fw < 1f || fh < 1f) min(fw, fh) else max(fw, fh)
        if (f != 1f) cm = FloatArray(n) { cm[it] * f }
        widthCm = extent { x, _ -> x }; heightCm = extent { _, y -> y }

        val squareness = solveSquareness(areaRatio(cm, widthCm, heightCm)).coerceIn(2.0f, 6.0f)
        val taper = taperRaw.coerceIn(-0.13f, 0.10f).let { if (abs(it) < 0.02f) 0f else it }
        val liftCm = (liftPx / pxPerMm / 10f) / 0.4f
        val lift = if (liftCm < 0.08f) 0f else liftCm.coerceIn(0f, 0.5f)
        val rimIsAcetate = rimMm >= 2.8f
        val rimStyle = if (rimIsAcetate) RimStyle.ACETATE else RimStyle.WIRE
        val rimCm = if (rimIsAcetate) (rimMm / 10f).coerceIn(0.34f, 0.62f) else (rimMm / 10f).coerceIn(0.15f, 0.22f)
        val topBoost = if (!solidLens && rimIsAcetate && rimPx > 0f && !doubleBridge) safeTopBoost(heightCm, rimCm, ((topT / rimPx) - 1f).let { if (it < 0.3f) 0f else it.coerceIn(0f, 1.0f) }) else 0f
        val bridgeCm = (gapPx / pxPerMm / 10f).coerceIn(1.5f, 2.2f)

        // ---- colour & pattern ----
        val look = decideLook(colourSamples, choice, rimStyle, lum(bg))
        val tint = when {
            category == Category.EYEGLASSES -> LensTint(rgb(190, 215, 225), 0.07f)
            solidLens && lensTintColour != 0 -> LensTint(lensTintColour or (0xFF shl 24), (0.5f + 0.4f * (1f - lum(lensTintColour) / 255f)).coerceIn(0.55f, 0.85f))
            else -> LensTint(rgb(24, 28, 34), 0.78f)
        }

        val spec = FrameSpec(
            id = id, name = name, category = category,
            shape = LensShape(widthCm, heightCm, squareness, taper = taper, lift = lift, topBoost = topBoost, radial = cm),
            rim = rimStyle, frameColor = look.base, accentColor = look.accent, pattern = look.pattern,
            metallic = rimStyle == RimStyle.WIRE, tint = tint, rimWidth = rimCm, bridgeWidth = bridgeCm, doubleBridge = doubleBridge
        )
        val summary = "Lens %.0f x %.0f mm, bridge %.0f mm, rim %.1f mm (%s)%s, %s".format(
            widthCm * 10f, heightCm * 10f, bridgeCm * 10f, rimMm, if (rimIsAcetate) "plastic" else "metal",
            if (doubleBridge) ", double bridge" else "", look.pattern.name.lowercase()
        )
        return PhotoResult.Ok(spec, summary)
    }

    /**
     * A thick browline rim on a tall lens would climb above the brows. The top of the frame is
     * lens centre + half the height + (path offset + profile half) = rim * (1 + boost); keep that below 5.75 cm.
     */
    fun safeTopBoost(heightCm: Float, rimCm: Float, boost: Float): Float {
        val room = (5.75f - EyewearGeometry.LENS_CENTER_Y - heightCm / 2f) / rimCm - 1f
        return boost.coerceAtMost(max(0f, room))
    }

    // ------------------------------------------------------------------------------------------------ outline helpers

    /** Pixels of hole [lab] that touch something that is not the hole. */
    private fun boundary(labels: IntArray, w: Int, h: Int, lab: Int, c: Comp): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (y in c.y0..c.y1) for (x in c.x0..c.x1) {
            if (labels[y * w + x] != lab) continue
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                labels[y * w + x - 1] != lab || labels[y * w + x + 1] != lab || labels[(y - 1) * w + x] != lab || labels[(y + 1) * w + x] != lab) out += intArrayOf(x, y)
        }
        return out
    }

    /** Convex hull (Andrew's monotone chain), counter-clockwise in a y-up sense; [pts] are (x, y) pixels. */
    private fun hullOf(pts: List<IntArray>): Array<FloatArray> {
        if (pts.size < 3) return Array(pts.size) { floatArrayOf(pts[it][0].toFloat(), pts[it][1].toFloat()) }
        val sorted = pts.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun cross(o: IntArray, a: IntArray, b: IntArray): Long = (a[0] - o[0]).toLong() * (b[1] - o[1]) - (a[1] - o[1]).toLong() * (b[0] - o[0])
        val hull = ArrayList<IntArray>()
        for (p in sorted) { while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1); hull += p }
        val lower = hull.size + 1
        for (i in sorted.size - 2 downTo 0) { val p = sorted[i]; while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1); hull += p }
        hull.removeAt(hull.size - 1)
        return Array(hull.size) { floatArrayOf(hull[it][0].toFloat(), hull[it][1].toFloat()) }
    }

    /** Distance from [c] to the hull boundary along [RADIAL_SAMPLES] directions (image space, y flipped so 90 degrees is "up"). */
    private fun radii(hull: Array<FloatArray>, c: FloatArray): FloatArray = FloatArray(RADIAL_SAMPLES) { k ->
        val t = 2.0 * PI * k / RADIAL_SAMPLES
        rayHull(c[0], c[1], cos(t).toFloat(), -sin(t).toFloat(), hull)
    }

    private fun rayHull(cx: Float, cy: Float, dx: Float, dy: Float, hull: Array<FloatArray>): Float {
        var best = 0f
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            val ex = b[0] - a[0]; val ey = b[1] - a[1]
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-6f) continue
            val t = ((a[0] - cx) * ey - (a[1] - cy) * ex) / den
            val u = ((a[0] - cx) * dy - (a[1] - cy) * dx) / den
            if (t > best && u >= -1e-3f && u <= 1.001f) best = t
        }
        return best
    }

    /** Area of the outline as a share of its bounding box (feeds [solveSquareness], reported for information). */
    private fun areaRatio(r: FloatArray, w: Float, h: Float): Float {
        var area = 0.0
        for (k in r.indices) { val t = 2.0 * PI / r.size; area += 0.5 * r[k] * r[(k + 1) % r.size] * sin(t) }
        return (area / (w * h).coerceAtLeast(1e-6f)).toFloat().coerceIn(0.5f, 1f)
    }

    private fun run(mask: BooleanArray, w: Int, h: Int, x: Int, y: Int, dx: Int, dy: Int): Float {
        var cx = x; var cy = y; var n = 0
        while (cx in 0 until w && cy in 0 until h && mask[cy * w + cx]) { n++; cx += dx; cy += dy }
        return n.toFloat()
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

    /** Samples the rim just outside the lens outline (so arms seen through the lens never pollute the colour). */
    private fun collectRimColours(px: IntArray, mask: BooleanArray, centre: FloatArray, prof: FloatArray, rimPx: Float, w: Int, h: Int, out: MutableList<Sample>) {
        for (k in 0 until RADIAL_SAMPLES step 2) {
            val t = 2.0 * PI * k / RADIAL_SAMPLES
            val dx = cos(t).toFloat(); val dy = -sin(t).toFloat()
            for (f in floatArrayOf(0.35f, 0.55f, 0.75f)) {
                val d = prof[k] + rimPx * f
                val x = (centre[0] + dx * d).roundToInt(); val y = (centre[1] + dy * d).roundToInt()
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

    private fun decideLook(samples: List<Sample>, choice: PatternChoice, rim: RimStyle, bgLum: Float): Look {
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
        // smoky/clear plastic photographed on a light background: grey, low saturation, with shading and highlights
        val crystalAuto = !tortoiseAuto && rim == RimStyle.ACETATE && hsv[1] < 0.16f && std >= 12f && lum(med) in 60f..215f && bgLum > 190f

        fun crystal(c: Int): Look = Look(mix(c, rgb(255, 255, 255), 0.15f), mix(c, rgb(255, 255, 255), 0.15f), Pattern.CRYSTAL)
        fun tortoise(): Look {
            val base = if (colourDist(dark, light) > 30f) dark else mix(med, rgb(40, 22, 10), 0.5f)
            val acc = if (colourDist(dark, light) > 30f) light else mix(med, rgb(235, 175, 95), 0.6f)
            return Look(base, acc, Pattern.TORTOISE)
        }
        return when (choice) {
            PatternChoice.SOLID -> Look(med, med, Pattern.SOLID)
            PatternChoice.TORTOISE -> tortoise()
            PatternChoice.CRYSTAL -> crystal(med)
            PatternChoice.TWO_TONE -> Look(topMed, botMed, Pattern.GRADIENT)
            PatternChoice.AUTO -> when {
                tortoiseAuto -> tortoise()
                crystalAuto -> crystal(med)
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

/** One frame <-> one text line, so user frames can be saved in a plain file and read back. Reads the older F1 format too. */
object FrameCodec {
    private const val V = "F2"
    fun encode(s: FrameSpec): String {
        val t = s.shape; val n = s.tint
        val name = s.name.replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
        val shape = listOf(t.width, t.height, t.squareness, t.taper, t.lift, t.topBoost, t.noseCut, t.facets, t.corner).joinToString(",") +
            (t.radial?.let { ";" + it.joinToString(",") } ?: "")
        return listOf(
            V, s.id, name, s.category.name, shape,
            s.rim.name, s.frameColor, s.accentColor, s.pattern.name, s.metallic,
            listOf(n.color, n.opacity, n.mirror, n.gradient).joinToString(","),
            listOf(s.rimWidth, s.rimDepth, s.bridgeWidth, s.wrap, s.templeThickness, s.templeHeight, s.doubleBridge).joinToString(",")
        ).joinToString("|")
    }

    fun decode(line: String): FrameSpec? = try {
        val p = line.split("|")
        if (p.size != 12 || (p[0] != V && p[0] != "F1")) null else {
            val shapeParts = p[4].split(";")
            val sh = shapeParts[0].split(",")
            val radial = shapeParts.getOrNull(1)?.split(",")?.map { it.toFloat() }?.toFloatArray()
            val ti = p[10].split(",")
            val ex = p[11].split(",")
            FrameSpec(
                id = p[1], name = p[2], category = Category.valueOf(p[3]),
                shape = LensShape(sh[0].toFloat(), sh[1].toFloat(), sh[2].toFloat(), sh[3].toFloat(), sh[4].toFloat(), sh[5].toFloat(), sh[6].toFloat(), sh[7].toInt(), sh[8].toFloat(), radial),
                rim = RimStyle.valueOf(p[5]), frameColor = p[6].toInt(), accentColor = p[7].toInt(),
                pattern = Pattern.valueOf(p[8]), metallic = p[9].toBoolean(),
                tint = LensTint(ti[0].toInt(), ti[1].toFloat(), ti[2].toFloat(), ti[3].toBoolean()),
                rimWidth = ex[0].toFloat(), rimDepth = ex[1].toFloat(), bridgeWidth = ex[2].toFloat(), wrap = ex[3].toFloat(),
                templeThickness = ex[4].toFloat(), templeHeight = ex[5].toFloat(), doubleBridge = ex.getOrNull(6)?.toBoolean() ?: false
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
