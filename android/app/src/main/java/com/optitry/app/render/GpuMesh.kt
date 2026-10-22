package com.optitry.app.render

import android.opengl.GLES20
import com.optitry.app.eyewear.MeshData
import com.optitry.app.eyewear.OccluderMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Interleaved vertex buffer (position, normal, aux) + index buffer on the GPU. GL thread only. */
internal class GpuMesh(mesh: MeshData) {
    private val buffers = IntArray(2)
    val indexCount = mesh.indices.size

    init {
        GLES20.glGenBuffers(2, buffers, 0)
        val vb = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(mesh.vertices).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.vertices.size * 4, vb, GLES20.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(mesh.indices).position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, ib, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun draw(aPos: Int, aNormal: Int, aAux: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, MeshData.STRIDE_BYTES, 0)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, MeshData.STRIDE_BYTES, 12)
        GLES20.glEnableVertexAttribArray(aAux)
        GLES20.glVertexAttribPointer(aAux, 2, GLES20.GL_FLOAT, false, MeshData.STRIDE_BYTES, 24)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aAux)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() = GLES20.glDeleteBuffers(2, buffers, 0)
}

/** Position-only mesh for the depth-only occluder. */
internal class GpuPositionMesh(mesh: OccluderMesh) {
    private val buffers = IntArray(2)
    val indexCount = mesh.indices.size

    init {
        GLES20.glGenBuffers(2, buffers, 0)
        val vb = ByteBuffer.allocateDirect(mesh.positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(mesh.positions).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.positions.size * 4, vb, GLES20.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(mesh.indices).position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, ib, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun draw(aPos: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() = GLES20.glDeleteBuffers(2, buffers, 0)
}

internal class GlProgram(vertex: String, fragment: String) {
    val id: Int
    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw IllegalStateException("Shader link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun attrib(name: String): Int = GLES20.glGetAttribLocation(id, name)
    fun uniform(name: String): Int = GLES20.glGetUniformLocation(id, name)
    fun use() = GLES20.glUseProgram(id)
    fun release() = GLES20.glDeleteProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }
}
