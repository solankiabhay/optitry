package com.optitry.app.eyewear

import com.optitry.app.core.Vec3

/** Interleaved vertices: position(3) normal(3) aux(2) = 8 floats. aux.x = part id, aux.y = 0..1 along the part. */
class MeshData(val vertices: FloatArray, val indices: ShortArray) {
    val vertexCount: Int get() = vertices.size / STRIDE_FLOATS
    val triangleCount: Int get() = indices.size / 3

    fun position(i: Int) = Vec3(vertices[i * STRIDE_FLOATS], vertices[i * STRIDE_FLOATS + 1], vertices[i * STRIDE_FLOATS + 2])
    fun normal(i: Int) = Vec3(vertices[i * STRIDE_FLOATS + 3], vertices[i * STRIDE_FLOATS + 4], vertices[i * STRIDE_FLOATS + 5])

    companion object {
        const val STRIDE_FLOATS = 8
        const val STRIDE_BYTES = STRIDE_FLOATS * 4
        const val MAX_VERTICES = 65535 // GLES2 guaranteed index type is unsigned short
    }
}

internal class MeshBuilder {
    private var verts = FloatArray(8192)
    private var vertFloats = 0
    private var idx = IntArray(16384)
    private var idxCount = 0

    val vertexCount: Int get() = vertFloats / MeshData.STRIDE_FLOATS

    fun vertex(p: Vec3, n: Vec3, part: Float, param: Float): Int {
        if (vertFloats + MeshData.STRIDE_FLOATS > verts.size) verts = verts.copyOf(verts.size * 2)
        val k = vertFloats
        verts[k] = p.x; verts[k + 1] = p.y; verts[k + 2] = p.z
        verts[k + 3] = n.x; verts[k + 4] = n.y; verts[k + 5] = n.z
        verts[k + 6] = part; verts[k + 7] = param
        vertFloats += MeshData.STRIDE_FLOATS
        return k / MeshData.STRIDE_FLOATS
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (idxCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[idxCount++] = a; idx[idxCount++] = b; idx[idxCount++] = c
    }

    fun build(): MeshData {
        check(vertexCount <= MeshData.MAX_VERTICES) { "mesh has $vertexCount vertices, limit is ${MeshData.MAX_VERTICES}" }
        return MeshData(verts.copyOf(vertFloats), ShortArray(idxCount) { idx[it].toShort() })
    }
}
