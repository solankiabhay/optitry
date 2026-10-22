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

    fun build(face: OccluderMesh): OccluderMesh {
        val facePos = FloatArray(face.positions.size)
        for (i in 0 until face.vertexCount) {
            facePos[i * 3] = face.positions[i * 3] * FACE_SHRINK
            facePos[i * 3 + 1] = SHRINK_CENTER_Y + (face.positions[i * 3 + 1] - SHRINK_CENTER_Y) * FACE_SHRINK
            facePos[i * 3 + 2] = face.positions[i * 3 + 2] * FACE_SHRINK
        }
        val rings = 14; val segs = 20
        val head = ArrayList<Float>()
        for (i in 0..rings) {
            val phi = PI * i / rings
            for (j in 0 until segs) {
                val th = 2.0 * PI * j / segs
                head.add((HEAD_RX * sin(phi) * cos(th)).toFloat())
                head.add((HEAD_CY + HEAD_RY * cos(phi)).toFloat())
                head.add((HEAD_CZ + HEAD_RZ * sin(phi) * sin(th)).toFloat())
            }
        }
        val base = face.vertexCount
        val idx = ArrayList<Short>()
        face.indices.forEach { idx.add(it) }
        for (i in 0 until rings) for (j in 0 until segs) {
            val j2 = (j + 1) % segs
            val a = base + i * segs + j; val b = base + i * segs + j2
            val c = base + (i + 1) * segs + j; val d = base + (i + 1) * segs + j2
            idx.add(a.toShort()); idx.add(c.toShort()); idx.add(b.toShort())
            idx.add(b.toShort()); idx.add(c.toShort()); idx.add(d.toShort())
        }
        return OccluderMesh(facePos + head.toFloatArray(), idx.toShortArray())
    }

    /** True if canonical-space point lies inside the (unshrunk-by-temple) head ellipsoid. */
    fun insideHead(x: Float, y: Float, z: Float): Boolean {
        val dx = x / HEAD_RX; val dy = (y - HEAD_CY) / HEAD_RY; val dz = (z - HEAD_CZ) / HEAD_RZ
        return dx * dx + dy * dy + dz * dz < 1f
    }
}
