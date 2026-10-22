package com.optitry.app.eyewear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Positions only (x,y,z per vertex) + triangle indices. Used for the invisible depth-only head occluder. */
class OccluderMesh(val positions: FloatArray, val indices: ShortArray) {
    val vertexCount: Int get() = positions.size / 3
}

object FaceMeshParser {
    /** Parses assets/face_mesh.txt: `v x y z` lines then `t a b c` zero-based triangle lines. */
    fun parse(text: String): OccluderMesh {
        val pos = ArrayList<Float>(468 * 3)
        val idx = ArrayList<Short>(898 * 3)
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val p = line.split(' ').filter { it.isNotEmpty() }
            when (p[0]) {
                "v" -> { pos.add(p[1].toFloat()); pos.add(p[2].toFloat()); pos.add(p[3].toFloat()) }
                "t" -> { idx.add(p[1].toShort()); idx.add(p[2].toShort()); idx.add(p[3].toShort()) }
            }
        }
        require(pos.size >= 3 && idx.size >= 3) { "face mesh asset is empty" }
        return OccluderMesh(pos.toFloatArray(), idx.toShortArray())
    }
}

object Occluders {
    /** Shrink the face mesh slightly toward the head centre so nose pads and the bridge rest ON the nose instead of z-fighting with it. */
    const val FACE_SHRINK = 0.97f
    private const val SHRINK_CENTER_Y = 1.0f

    // Skull ellipsoid behind the face mesh (which ends at the ear front, z~-2.4). Hides the far temple when the head turns.
    const val HEAD_CY = 1.0f
    const val HEAD_CZ = -3.8f
    const val HEAD_RX = 7.7f
    const val HEAD_RY = 10.5f
    const val HEAD_RZ = 9.0f

    // Pinna (outer ear) proxies. Without them the part of the arm that hooks behind the ear would be drawn ON TOP of the ear.
    // Top of the ear ~2 cm above the pupils (y=4.0), lobe near the nose base (y=-1.7); the ear stands ~1 cm proud of the skull.
    const val EAR_CX = 8.20f
    const val EAR_CY = 1.15f
    const val EAR_CZ = -4.30f
    const val EAR_RX = 0.95f
    const val EAR_RY = 2.85f
    const val EAR_RZ = 2.00f

    /** Appends an ellipsoid (rings x segs grid, poles included) and returns nothing; vertex order matches the original head proxy. */
    private fun appendEllipsoid(
        pos: ArrayList<Float>, idx: ArrayList<Short>,
        cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, rings: Int, segs: Int
    ) {
        val base = pos.size / 3
        for (i in 0..rings) {
            val phi = PI * i / rings
            for (j in 0 until segs) {
                val th = 2.0 * PI * j / segs
                pos.add(cx + (rx * sin(phi) * cos(th)).toFloat())
                pos.add(cy + (ry * cos(phi)).toFloat())
                pos.add(cz + (rz * sin(phi) * sin(th)).toFloat())
            }
        }
        for (i in 0 until rings) for (j in 0 until segs) {
            val j2 = (j + 1) % segs
            val a = base + i * segs + j; val b = base + i * segs + j2
            val c = base + (i + 1) * segs + j; val d = base + (i + 1) * segs + j2
            idx.add(a.toShort()); idx.add(c.toShort()); idx.add(b.toShort())
            idx.add(b.toShort()); idx.add(c.toShort()); idx.add(d.toShort())
        }
    }

    fun build(face: OccluderMesh): OccluderMesh {
        val pos = ArrayList<Float>(face.positions.size + 1500)
        val idx = ArrayList<Short>(face.indices.size + 3000)
        for (i in 0 until face.vertexCount) {
            pos.add(face.positions[i * 3] * FACE_SHRINK)
            pos.add(SHRINK_CENTER_Y + (face.positions[i * 3 + 1] - SHRINK_CENTER_Y) * FACE_SHRINK)
            pos.add(face.positions[i * 3 + 2] * FACE_SHRINK)
        }
        face.indices.forEach { idx.add(it) }
        appendEllipsoid(pos, idx, 0f, HEAD_CY, HEAD_CZ, HEAD_RX, HEAD_RY, HEAD_RZ, 14, 20)
        appendEllipsoid(pos, idx, EAR_CX, EAR_CY, EAR_CZ, EAR_RX, EAR_RY, EAR_RZ, 10, 14)
        appendEllipsoid(pos, idx, -EAR_CX, EAR_CY, EAR_CZ, EAR_RX, EAR_RY, EAR_RZ, 10, 14)
        return OccluderMesh(pos.toFloatArray(), idx.toShortArray())
    }

    /** True if the point lies inside the pinna proxy of the given side (+1 right / -1 left), shrunk by [shrink] (1 = full size). */
    fun insideEar(x: Float, y: Float, z: Float, side: Float, shrink: Float = 1f): Boolean {
        val dx = (x - side * EAR_CX) / (EAR_RX * shrink)
        val dy = (y - EAR_CY) / (EAR_RY * shrink)
        val dz = (z - EAR_CZ) / (EAR_RZ * shrink)
        return dx * dx + dy * dy + dz * dz < 1f
    }

    /** True if canonical-space point lies inside the (unshrunk-by-temple) head ellipsoid. */
    fun insideHead(x: Float, y: Float, z: Float): Boolean {
        val dx = x / HEAD_RX; val dy = (y - HEAD_CY) / HEAD_RY; val dz = (z - HEAD_CZ) / HEAD_RZ
        return dx * dx + dy * dy + dz * dz < 1f
    }
}
