package com.optitry.app.eyewear

import com.optitry.app.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Frame + lenses in MediaPipe canonical head space (cm). Frame is opaque/crystal, lenses are alpha blended. */
class GlassesMesh(val frame: MeshData, val lenses: MeshData, val hingeX: Float)

/**
 * Procedural 3D eyewear. Every part is a real swept solid with thickness (rim bands, bridge, end
 * pieces, temples with an ear bend, hinges, nose pads) plus domed lens surfaces. All coordinates are in
 * the canonical face model space that MediaPipe's facial transformation matrix maps onto the face:
 * +x right, +y up, +z out of the face, ~6.3 cm between pupils, nose bridge at z~5.8.
 */
object EyewearGeometry {
    const val FRAME_Z0 = 5.85f         // front plane of the frame at the nose bridge
    const val LENS_CENTER_Y = 2.45f    // eyes (pupils) sit at y~2.6
    const val PART_BODY = 0f
    const val PART_TEMPLE = 1f
    const val PART_PAD = 2f
    const val PART_HINGE = 3f
    private const val CONTOUR_SEGMENTS = 72
    private const val LENS_RINGS = 5
    private const val LENS_BULGE = 0.10f
    private const val MIN_HINGE_X = 6.30f
    private const val MAX_HINGE_X = 7.45f

    // Temple centreline (x, y relative to hinge, z) running outside the head to the ear and bending down behind it.
    // x values keep a clearance from the canonical face surface (x~6.4 at z=2, 7.2 at z=0, 7.7 at z=-2).
    private val TEMPLE_X = floatArrayOf(0f, 7.30f, 7.70f, 8.10f, 8.20f, 8.10f, 7.90f)
    private val TEMPLE_DY = floatArrayOf(0f, -0.05f, -0.10f, -0.20f, -0.55f, -1.75f, -3.25f)
    private val TEMPLE_Z = floatArrayOf(0f, 2.0f, -0.5f, -3.2f, -5.0f, -6.2f, -6.6f)

    fun frameZ(spec: FrameSpec, x: Float) = FRAME_Z0 - spec.wrap * x * x

    fun build(spec: FrameSpec): GlassesMesh {
        val frame = MeshBuilder()
        val lens = MeshBuilder()
        val local = spec.shape.contour(CONTOUR_SEGMENTS)
        val b = spec.shape.height / 2f
        val minX = local.minOf { it[0] }
        val cx = spec.bridgeWidth / 2f - minX
        val mirroredLocal = local.map { floatArrayOf(-it[0], it[1]) }.reversed()

        val right = Lens(spec, local, +cx, b)
        val left = Lens(spec, mirroredLocal, -cx, b)

        for (l in listOf(right, left)) {
            addRim(frame, spec, l)
            addLens(lens, spec, l)
        }

        // Hinge position derived from the outer rim wall of the right lens (left mirrors it).
        val outer = right.outerPieceIndex
        val outerRim = right.rimPath[outer]
        val hingeX = (outerRim.x + 0.30f).coerceIn(MIN_HINGE_X, MAX_HINGE_X).let { max(it, min(outerRim.x + 0.12f, 7.6f)) }
        val hingeY = outerRim.y + 0.05f

        for (side in listOf(+1f, -1f)) {
            val l = if (side > 0) right else left
            addEndPiece(frame, spec, l, side, hingeX, hingeY)
            addTemple(frame, spec, side, hingeX, hingeY)
            addHinge(frame, side, hingeX, hingeY, spec)
        }
        addBridge(frame, spec, right, left)
        addNosePads(frame, spec, right, left)
        return GlassesMesh(frame.build(), lens.build(), hingeX)
    }

    // ---- lens/rim description ------------------------------------------------------------------------------

    private class Lens(val spec: FrameSpec, val contour: List<FloatArray>, val centerX: Float, val halfHeight: Float) {
        val n = contour.size
        val sign = if (centerX >= 0f) 1f else -1f
        /** Per-point rim width factor (browline thickens the top). */
        val widthScale = FloatArray(n) { i ->
            val t = ((contour[i][1] / halfHeight) - 0.1f) / 0.9f
            1f + spec.shape.topBoost * t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        }
        val rimPath: List<Vec3>
        val outward: List<Vec3>
        val outerPieceIndex: Int
        val bridgeIndex: Int

        init {
            val outs = ArrayList<Vec3>(n)
            val path = ArrayList<Vec3>(n)
            for (i in 0 until n) {
                val prev = contour[(i + n - 1) % n]; val next = contour[(i + 1) % n]
                val tx = next[0] - prev[0]; val ty = next[1] - prev[1]
                val len = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(1e-6f)
                val ox = ty / len; val oy = -tx / len // outward for a counter-clockwise contour
                val off = spec.rimWidth * widthScale[i] * 0.5f
                val x = centerX + contour[i][0] + ox * off
                val y = LENS_CENTER_Y + contour[i][1] + oy * off
                outs.add(Vec3(ox, oy, 0f))
                path.add(Vec3(x, y, frameZ(spec, x)))
            }
            outward = outs
            rimPath = path
            // end piece: outermost point in the upper half of the lens; bridge: innermost point just above centre
            var bestOuter = 0; var bestX = -1e9f
            var bestInner = 0; var bestInnerX = 1e9f
            for (i in 0 until n) {
                val ly = contour[i][1]
                val gx = abs(centerX + contour[i][0])
                if (ly >= 0.05f * halfHeight && ly <= 0.5f * halfHeight && gx > bestX) { bestX = gx; bestOuter = i }
                if (ly >= 0.10f * halfHeight && ly <= 0.45f * halfHeight && gx < bestInnerX) { bestInnerX = gx; bestInner = i }
            }
            outerPieceIndex = bestOuter
            bridgeIndex = bestInner
        }
    }

    private fun addRim(mb: MeshBuilder, spec: FrameSpec, l: Lens) {
        val profile = if (spec.rim == RimStyle.ACETATE)
            Profiles.roundedRect(spec.rimWidth, spec.rimDepth, min(spec.rimWidth, spec.rimDepth) * 0.38f, 3)
        else Profiles.circle(spec.rimWidth * 0.5f, 10)
        val su = if (spec.rim == RimStyle.ACETATE) l.widthScale else FloatArray(l.n) { 1f }
        Sweeper.sweep(mb, l.rimPath, closed = true, profile = profile, uHint = l.outward, su = su, sv = null, part = PART_BODY, caps = false)
    }

    private fun addLens(mb: MeshBuilder, spec: FrameSpec, l: Lens) {
        val ax = spec.shape.width / 2f
        val ay = spec.shape.height / 2f
        val rings = LENS_RINGS
        fun heightAt(gx: Float, f: Float) = frameZ(spec, gx) + LENS_BULGE * (1f - f * f)
        val centre = run {
            val gx = l.centerX
            val gy = LENS_CENTER_Y
            val nx = -(-2f * spec.wrap * gx)
            mb.vertex(Vec3(gx, gy, heightAt(gx, 0f)), Vec3(nx, 0f, 1f).normalized(), 0f, 0f)
        }
        val ringStart = IntArray(rings + 1)
        ringStart[0] = centre
        for (r in 1..rings) {
            val f = r.toFloat() / rings
            ringStart[r] = mb.vertexCount
            for (k in 0 until l.n) {
                val lx = l.contour[k][0] * f
                val ly = l.contour[k][1] * f
                val gx = l.centerX + lx
                val gy = LENS_CENTER_Y + ly
                val hx = -2f * spec.wrap * gx - 2f * LENS_BULGE * lx / (ax * ax * 1.0f)
                val hy = -2f * LENS_BULGE * ly / (ay * ay)
                mb.vertex(Vec3(gx, gy, heightAt(gx, f)), Vec3(-hx, -hy, 1f).normalized(), 0f, f)
            }
        }
        for (k in 0 until l.n) mb.triangle(centre, ringStart[1] + k, ringStart[1] + (k + 1) % l.n)
        for (r in 1 until rings) for (k in 0 until l.n) {
            val a = ringStart[r] + k; val bb = ringStart[r] + (k + 1) % l.n
            val c = ringStart[r + 1] + k; val d = ringStart[r + 1] + (k + 1) % l.n
            mb.triangle(a, c, d); mb.triangle(a, d, bb)
        }
    }

    private fun addBridge(mb: MeshBuilder, spec: FrameSpec, right: Lens, left: Lens) {
        val p0 = right.rimPath[right.bridgeIndex]
        val p2 = left.rimPath[left.bridgeIndex]
        val mid = Vec3(0f, (p0.y + p2.y) / 2f + 0.40f, frameZ(spec, 0f) + 0.10f)
        val ctrl = Vec3(0f, mid.y + 0.42f, mid.z) // quadratic Bezier control point so the apex lands near `mid`
        val pts = (0..14).map { i ->
            val t = i / 14f
            val a = (1 - t) * (1 - t); val bq = 2 * (1 - t) * t; val c = t * t
            Vec3(p0.x * a + ctrl.x * bq + p2.x * c, p0.y * a + ctrl.y * bq + p2.y * c, p0.z * a + ctrl.z * bq + p2.z * c)
        }
        val profile = if (spec.rim == RimStyle.ACETATE) Profiles.roundedRect(0.40f, spec.rimDepth * 0.9f, 0.14f, 3) else Profiles.circle(0.055f, 8)
        Sweeper.sweep(mb, pts, false, profile, pts.map { Vec3(0f, 1f, 0f) }, null, null, PART_BODY, true)
    }

    private const val PAD_X = 1.07f   // on the nose side: surface z~5.07 at x=1.0, y=1.6 and slopes ~2 cm per cm outward
    private const val PAD_Y = 1.60f
    private const val PAD_Z = 5.11f
    private const val PAD_YAW = 1.10f   // thin axis along the surface normal (~63 deg toward the side)

    private fun addNosePads(mb: MeshBuilder, spec: FrameSpec, right: Lens, left: Lens) {
        for (l in listOf(right, left)) {
            val s = l.sign
            val padX = s * PAD_X
            val arcStart = l.rimPath[l.bridgeIndex]
            val end = Vec3(padX, PAD_Y + 0.15f, PAD_Z + 0.05f)
            val arm = listOf(
                arcStart,
                Vec3((arcStart.x + end.x) / 2f, (arcStart.y + end.y) / 2f, (arcStart.z + end.z) / 2f + 0.10f),
                end
            )
            val prof = if (spec.rim == RimStyle.ACETATE) Profiles.roundedRect(0.22f, 0.22f, 0.07f, 2) else Profiles.circle(0.04f, 6)
            Sweeper.sweep(mb, arm, false, prof, arm.map { Vec3(1f, 0f, 0f) }, null, null, PART_BODY, true)
            Sweeper.ellipsoid(mb, Vec3(padX, PAD_Y, PAD_Z), Vec3(0.20f, 0.30f, 0.09f), yaw = s * PAD_YAW, part = PART_PAD, segments = 10)
        }
    }

    private fun addEndPiece(mb: MeshBuilder, spec: FrameSpec, l: Lens, side: Float, hingeX: Float, hingeY: Float) {
        val a = l.rimPath[l.outerPieceIndex]
        val hx = side * hingeX
        val pts = (0..4).map { i ->
            val t = i / 4f
            val x = a.x + (hx - a.x) * t
            Vec3(x, a.y + (hingeY - a.y) * t, frameZ(spec, x))
        }
        val prof = if (spec.rim == RimStyle.ACETATE) Profiles.roundedRect(0.34f, spec.rimDepth, 0.12f, 3) else Profiles.circle(0.05f, 8)
        Sweeper.sweep(mb, pts, false, prof, pts.map { Vec3(0f, 1f, 0f) }, null, null, PART_BODY, true)
    }

    private fun addHinge(mb: MeshBuilder, side: Float, hingeX: Float, hingeY: Float, spec: FrameSpec) {
        val x = side * hingeX
        val z = frameZ(spec, x)
        val h = max(spec.templeHeight * 0.6f, 0.18f)
        val pts = listOf(Vec3(x, hingeY - h, z - 0.02f), Vec3(x, hingeY + h, z - 0.02f))
        Sweeper.sweep(mb, pts, false, Profiles.circle(0.075f, 8), pts.map { Vec3(1f, 0f, 0f) }, null, null, PART_HINGE, true)
    }

    private fun addTemple(mb: MeshBuilder, spec: FrameSpec, side: Float, hingeX: Float, hingeY: Float) {
        val hingeZ = frameZ(spec, hingeX) - 0.02f
        val controls = TEMPLE_X.indices.map { i ->
            val x = if (i == 0) hingeX else max(TEMPLE_X[i], hingeX + 0.02f * i)
            Vec3(side * x, hingeY + TEMPLE_DY[i], if (i == 0) hingeZ else TEMPLE_Z[i])
        }
        val pts = Sweeper.catmullRom(controls, 7)
        val m = pts.size
        val hint = pts.map { Vec3(side, 0f, 0f) }
        val acetate = spec.rim == RimStyle.ACETATE
        val profile = if (acetate) Profiles.roundedRect(spec.templeThickness, spec.templeHeight, min(spec.templeThickness, spec.templeHeight) * 0.4f, 3)
        else Profiles.circle(spec.templeHeight * 0.5f, 8)
        val sv = FloatArray(m) { i ->
            val t = i / (m - 1f)
            if (acetate) 1f - 0.40f * smooth(0.55f, 1f, t) else 1f + 0.15f * smooth(0.82f, 1f, t) // wire arms get a slightly fatter tip
        }
        Sweeper.sweep(mb, pts, false, profile, hint, null, sv, PART_TEMPLE, true)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** 2D cross-sections (u, v) in counter-clockwise order with analytic outward normals. */
internal class Profile(val pts: List<FloatArray>, val normals: List<FloatArray>)

internal object Profiles {
    fun circle(r: Float, segments: Int): Profile {
        val pts = ArrayList<FloatArray>(); val nrm = ArrayList<FloatArray>()
        for (i in 0 until segments) {
            val t = 2.0 * PI * i / segments
            val c = cos(t).toFloat(); val s = sin(t).toFloat()
            pts.add(floatArrayOf(c * r, s * r)); nrm.add(floatArrayOf(c, s))
        }
        return Profile(pts, nrm)
    }

    fun roundedRect(w: Float, h: Float, radius: Float, arcSegments: Int): Profile {
        val r = min(radius, min(w, h) / 2f - 1e-4f).coerceAtLeast(1e-3f)
        val pts = ArrayList<FloatArray>(); val nrm = ArrayList<FloatArray>()
        val centres = arrayOf(floatArrayOf(w / 2f - r, h / 2f - r), floatArrayOf(-(w / 2f - r), h / 2f - r),
            floatArrayOf(-(w / 2f - r), -(h / 2f - r)), floatArrayOf(w / 2f - r, -(h / 2f - r)))
        for (corner in 0 until 4) {
            for (s in 0..arcSegments) {
                val t = (corner * 90.0 + 90.0 * s / arcSegments) * PI / 180.0
                val c = cos(t).toFloat(); val sn = sin(t).toFloat()
                pts.add(floatArrayOf(centres[corner][0] + c * r, centres[corner][1] + sn * r)); nrm.add(floatArrayOf(c, sn))
            }
        }
        return Profile(pts, nrm)
    }
}

internal object Sweeper {
    /**
     * Sweeps [profile] along [path]. Frame at each point: tangent T, u = [uHint] made orthogonal to T,
     * v = T x u, so (u, v, T) is right-handed and the profile is counter-clockwise seen along -T.
     */
    fun sweep(
        mb: MeshBuilder, path: List<Vec3>, closed: Boolean, profile: Profile, uHint: List<Vec3>,
        su: FloatArray?, sv: FloatArray?, part: Float, caps: Boolean
    ) {
        val n = path.size
        val m = profile.pts.size
        val first = mb.vertexCount
        val frames = ArrayList<Triple<Vec3, Vec3, Vec3>>(n)
        for (i in 0 until n) {
            val prev = if (closed) path[(i + n - 1) % n] else path[max(i - 1, 0)]
            val next = if (closed) path[(i + 1) % n] else path[min(i + 1, n - 1)]
            val t = (next - prev).normalized()
            var u = uHint[i] - t * uHint[i].dot(t)
            if (u.length() < 1e-5f) u = Vec3(0f, 1f, 0f) - t * t.y
            u = u.normalized()
            val v = t.cross(u).normalized()
            frames.add(Triple(u, v, t))
        }
        for (i in 0 until n) {
            val (u, v, _) = frames[i]
            val a = su?.get(i) ?: 1f
            val b = sv?.get(i) ?: 1f
            val param = if (n > 1) i / (n - 1f) else 0f
            for (j in 0 until m) {
                val (pu, pv) = profile.pts[j]
                val (nu, nv) = profile.normals[j]
                val p = path[i] + u * (pu * a) + v * (pv * b)
                val nrm = (u * (nu / a) + v * (nv / b)).normalized()
                mb.vertex(p, nrm, part, param)
            }
        }
        val rings = if (closed) n else n - 1
        for (i in 0 until rings) {
            val i2 = (i + 1) % n
            for (j in 0 until m) {
                val j2 = (j + 1) % m
                val a = first + i * m + j; val b = first + i * m + j2
                val c = first + i2 * m + j; val d = first + i2 * m + j2
                mb.triangle(a, b, c); mb.triangle(b, d, c)
            }
        }
        if (caps && !closed) {
            cap(mb, path[0], frames[0], profile, su?.get(0) ?: 1f, sv?.get(0) ?: 1f, part, 0f, start = true)
            cap(mb, path[n - 1], frames[n - 1], profile, su?.get(n - 1) ?: 1f, sv?.get(n - 1) ?: 1f, part, 1f, start = false)
        }
    }

    private fun cap(mb: MeshBuilder, p: Vec3, f: Triple<Vec3, Vec3, Vec3>, profile: Profile, a: Float, b: Float, part: Float, param: Float, start: Boolean) {
        val (u, v, t) = f
        val normal = if (start) t * -1f else t
        val centre = mb.vertex(p, normal, part, param)
        val ring = profile.pts.map { (pu, pv) -> mb.vertex(p + u * (pu * a) + v * (pv * b), normal, part, param) }
        for (j in ring.indices) {
            val j2 = (j + 1) % ring.size
            if (start) mb.triangle(centre, ring[j2], ring[j]) else mb.triangle(centre, ring[j], ring[j2])
        }
    }

    fun ellipsoid(mb: MeshBuilder, c: Vec3, radii: Vec3, yaw: Float, part: Float, segments: Int) {
        val rings = segments
        val first = mb.vertexCount
        val cy = cos(yaw); val sy = sin(yaw)
        fun rot(v: Vec3) = Vec3(v.x * cy + v.z * sy, v.y, -v.x * sy + v.z * cy)
        for (i in 0..rings) {
            val phi = PI * i / rings
            for (j in 0 until segments) {
                val th = 2.0 * PI * j / segments
                val dir = Vec3((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat())
                val pos = rot(Vec3(dir.x * radii.x, dir.y * radii.y, dir.z * radii.z))
                val nrm = rot(Vec3(dir.x / radii.x, dir.y / radii.y, dir.z / radii.z)).normalized()
                mb.vertex(c + pos, nrm, part, 0f)
            }
        }
        for (i in 0 until rings) for (j in 0 until segments) {
            val j2 = (j + 1) % segments
            val a = first + i * segments + j; val b = first + i * segments + j2
            val cc = first + (i + 1) * segments + j; val d = first + (i + 1) * segments + j2
            mb.triangle(a, b, cc); mb.triangle(b, d, cc)
        }
    }

    fun catmullRom(pts: List<Vec3>, samples: Int): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (i in 0 until pts.size - 1) {
            val p0 = pts[max(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[min(i + 2, pts.size - 1)]
            for (s in 0 until samples) {
                val t = s.toFloat() / samples; val t2 = t * t; val t3 = t2 * t
                out.add(((p1 * 2f) + (p2 - p0) * t + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * t2 + (p1 * 3f - p0 - p2 * 3f + p3) * t3) * 0.5f)
            }
        }
        out.add(pts.last())
        return out
    }
}
