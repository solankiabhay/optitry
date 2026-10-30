package com.optitry.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.optitry.app.core.ContactLens
import com.optitry.app.core.Eye
import com.optitry.app.core.EyeMapping
import com.optitry.app.core.Eyes
import com.optitry.app.core.FacePose
import com.optitry.app.core.FitMode
import com.optitry.app.core.Mat4
import com.optitry.app.core.ViewportMapping
import com.optitry.app.eyewear.EyewearGeometry
import com.optitry.app.eyewear.FaceMeshParser
import com.optitry.app.eyewear.FrameSpec
import com.optitry.app.eyewear.GlassesMesh
import com.optitry.app.eyewear.Occluders
import com.optitry.app.eyewear.Pattern
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws, in one GL surface: the camera/photo frame, an invisible depth-only head occluder, the opaque
 * 3D frame, and the alpha-blended lenses. Everything is in MediaPipe's virtual-camera space, so the
 * facial transformation matrix is applied directly as the model matrix.
 */
class GlassesRenderer(
    private val appContext: Context,
    private val onError: (String) -> Unit
) : GLSurfaceView.Renderer {

    val ring = FrameRing()
    @Volatile var requestRender: () -> Unit = {}

    @Volatile private var desiredSpec: FrameSpec? = null
    @Volatile private var captureCallback: ((Bitmap?) -> Unit)? = null
    @Volatile private var clearRequested = false

    /** Contact lens to draw on both irises (null = none) and its strength 0..1. */
    @Volatile var lens: ContactLens? = null
        set(value) { field = value; requestRender() }
    @Volatile var lensOpacity: Float = 0.72f
        set(value) { field = value.coerceIn(0.1f, 1f); requestRender() }
    /** False while the contact-lens tab is open (glasses hidden). */
    @Volatile var glassesVisible: Boolean = true
        set(value) { field = value; requestRender() }

    // ---- GL-thread state ----
    private var bg: GlProgram? = null
    private var occ: GlProgram? = null
    private var frameProg: GlProgram? = null
    private var lensProg: GlProgram? = null
    private var maskProg: GlProgram? = null
    private var irisProg: GlProgram? = null
    private var irisQuad: FloatBuffer? = null
    private var maskBuf: FloatBuffer? = null
    private var quadBuffer = 0
    private var texture = 0
    private var texW = 0
    private var texH = 0
    private var viewW = 1
    private var viewH = 1
    private var imageW = 0
    private var imageH = 0
    private var currentPose: FacePose? = null
    private var currentEyes: Eyes? = null
    private var ambient = 1f
    private var mirror = true
    private var hasFrame = false

    private var occluder: GpuPositionMesh? = null
    private var builtSpec: FrameSpec? = null
    private var frameMesh: GpuMesh? = null
    private var lensMesh: GpuMesh? = null
    private var failed = false

    fun setSpec(spec: FrameSpec) {
        desiredSpec = spec
        requestRender()
    }

    /** Asks for the next fully rendered frame as a bitmap (what's on screen, including the mirrored selfie view). */
    fun capture(callback: (Bitmap?) -> Unit) {
        captureCallback = callback
        requestRender()
    }

    /** Drop the current picture (used when switching between live camera and a photo). */
    fun clearFrame() {
        clearRequested = true
        ring.clear()
        requestRender()
    }

    // ------------------------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            releaseGl()
            bg = GlProgram(Shaders.BACKGROUND_VS, Shaders.BACKGROUND_FS)
            occ = GlProgram(Shaders.OCCLUDER_VS, Shaders.OCCLUDER_FS)
            frameProg = GlProgram(Shaders.MESH_VS, Shaders.FRAME_FS)
            lensProg = GlProgram(Shaders.MESH_VS, Shaders.LENS_FS)
            maskProg = GlProgram(Shaders.MASK_VS, Shaders.MASK_FS)
            irisProg = GlProgram(Shaders.IRIS_VS, Shaders.IRIS_FS)
            irisQuad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            maskBuf = ByteBuffer.allocateDirect(40 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val qb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            qb.put(quad).position(0)
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            quadBuffer = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, qb, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texture = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texW = 0; texH = 0; hasFrame = false

            val faceText = appContext.assets.open("face_mesh.txt").bufferedReader().use { it.readText() }
            occluder = GpuPositionMesh(Occluders.build(FaceMeshParser.parse(faceText)))
            builtSpec = null // force re-upload of the glasses meshes into the new context
            failed = false
        } catch (e: Throwable) {
            fail("3D renderer could not start", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewW, viewH)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (failed) return
        try {
            draw()
        } catch (e: Throwable) {
            fail("3D rendering failed", e)
        }
    }

    private fun draw() {
        if (clearRequested) {
            clearRequested = false
            hasFrame = false
            currentPose = null
            currentEyes = null
        }
        consumeFrame()
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0.04f, 0.04f, 0.06f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)

        if (hasFrame && imageW > 0) {
            drawBackground()
            val lensNow = lens
            val eyes = currentEyes
            if (lensNow != null && eyes != null) drawContactLenses(lensNow, eyes)
            val pose = currentPose
            val spec = desiredSpec
            if (glassesVisible && pose != null && spec != null) {
                ensureGlasses(spec)
                drawGlasses(pose, spec)
            }
        }

        val cb = captureCallback
        if (cb != null) {
            captureCallback = null
            cb(readPixels())
        }
    }

    private fun consumeFrame() {
        val slot = ring.takeLatest() ?: return
        try {
            val bmp = slot.bitmap
            if (bmp != null && !bmp.isRecycled) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                if (texW != bmp.width || texH != bmp.height) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    texW = bmp.width; texH = bmp.height
                } else {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                }
                imageW = bmp.width; imageH = bmp.height
                currentPose = slot.pose
                currentEyes = slot.eyes
                ambient = slot.ambient
                mirror = slot.mirror
                hasFrame = true
            }
        } finally {
            ring.release()
        }
    }

    private fun drawBackground() {
        val p = bg ?: return
        val (vx, vy) = ViewportMapping.visibleFraction(imageW, imageH, viewW, viewH, FitMode.COVER)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        p.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(p.uniform("uTex"), 0)
        GLES20.glUniform2f(p.uniform("uVisible"), vx, vy)
        GLES20.glUniform1f(p.uniform("uMirror"), if (mirror) 1f else 0f)
        val a = p.attrib("aPos")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(a).position(0) }

    /** Cosmetic lens on both irises, clipped to the eyelid opening so blinks and half-closed eyes look right. */
    private fun drawContactLenses(lens: ContactLens, eyes: Eyes) {
        val mp = maskProg ?: return
        val ip = irisProg ?: return
        val quad = irisQuad ?: return
        val buf = maskBuf ?: return
        val pxScale = EyeMapping.scale(imageW, imageH, viewW, viewH, FitMode.COVER)
        val (vx, vy) = ViewportMapping.visibleFraction(imageW, imageH, viewW, viewH, FitMode.COVER)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_STENCIL_TEST)

        // 1) eyelid openings -> stencil
        GLES20.glStencilMask(0xFF)
        GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT)
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 1, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)
        GLES20.glColorMask(false, false, false, false)
        mp.use()
        val aMask = mp.attrib("aPos")
        for (eye in listOf(eyes.right, eyes.left)) {
            val n = eye.contour.size / 2
            if (n < 3 || n > 38) continue
            buf.position(0)
            var cx = 0f; var cy = 0f
            val ndc = FloatArray(n * 2)
            for (i in 0 until n) {
                val p = EyeMapping.toView(eye.contour[i * 2], eye.contour[i * 2 + 1], imageW, imageH, viewW, viewH, FitMode.COVER, mirror)
                ndc[i * 2] = p[0] / viewW * 2f - 1f
                ndc[i * 2 + 1] = 1f - p[1] / viewH * 2f
                cx += ndc[i * 2]; cy += ndc[i * 2 + 1]
            }
            buf.put(cx / n).put(cy / n)
            for (i in 0 until n) buf.put(ndc[i * 2]).put(ndc[i * 2 + 1])
            buf.put(ndc[0]).put(ndc[1])
            buf.position(0)
            GLES20.glEnableVertexAttribArray(aMask)
            GLES20.glVertexAttribPointer(aMask, 2, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, n + 2)
            GLES20.glDisableVertexAttribArray(aMask)
        }
        GLES20.glColorMask(true, true, true, true)

        // 2) irises, only where the stencil says the eye is open
        GLES20.glStencilFunc(GLES20.GL_EQUAL, 1, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        ip.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(ip.uniform("uTex"), 0)
        GLES20.glUniform2f(ip.uniform("uView"), viewW.toFloat(), viewH.toFloat())
        GLES20.glUniform2f(ip.uniform("uVisible"), vx, vy)
        GLES20.glUniform1f(ip.uniform("uMirror"), if (mirror) 1f else 0f)
        val c1 = rgb(lens.color); val c2 = rgb(lens.color2)
        GLES20.glUniform3f(ip.uniform("uColor"), c1[0], c1[1], c1[2])
        GLES20.glUniform3f(ip.uniform("uColor2"), c2[0], c2[1], c2[2])
        GLES20.glUniform1f(ip.uniform("uOpacity"), lensOpacity)
        GLES20.glUniform1f(ip.uniform("uPupil"), 0.34f)
        val aLocal = ip.attrib("aLocal")
        for (eye: Eye in listOf(eyes.right, eyes.left)) {
            val c = EyeMapping.toView(eye.u, eye.v, imageW, imageH, viewW, viewH, FitMode.COVER, mirror)
            val r = eye.radiusPx * pxScale * 1.04f
            GLES20.glUniform2f(ip.uniform("uCenter"), c[0] / viewW * 2f - 1f, 1f - c[1] / viewH * 2f)
            GLES20.glUniform2f(ip.uniform("uRadiusNdc"), r * 2f / viewW, r * 2f / viewH)
            quad.position(0)
            GLES20.glEnableVertexAttribArray(aLocal)
            GLES20.glVertexAttribPointer(aLocal, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aLocal)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
    }

    private fun ensureGlasses(spec: FrameSpec) {
        if (builtSpec?.id == spec.id && frameMesh != null) return
        frameMesh?.release(); lensMesh?.release()
        val mesh: GlassesMesh = EyewearGeometry.build(spec)
        frameMesh = GpuMesh(mesh.frame)
        lensMesh = GpuMesh(mesh.lenses)
        builtSpec = spec
    }

    private fun drawGlasses(pose: FacePose, spec: FrameSpec) {
        val proj = ViewportMapping.projection(imageW, imageH, viewW, viewH, FitMode.COVER, mirror)
        val model = pose.toMatrix()
        val mvp = Mat4.multiply(proj, model)
        if (!Mat4.isFinite(mvp)) return

        GLES20.glDisable(GLES20.GL_CULL_FACE) // mirrored projection flips winding; the shaders are two-sided
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)

        // 1) invisible head: writes depth only, so temples / far hinge vanish behind the face when the head turns
        val o = occ
        val om = occluder
        if (o != null && om != null) {
            o.use()
            GLES20.glUniformMatrix4fv(o.uniform("uMVP"), 1, false, mvp, 0)
            GLES20.glColorMask(false, false, false, false)
            om.draw(o.attrib("aPos"))
            GLES20.glColorMask(true, true, true, true)
        }

        val fp = frameProg ?: return
        val lp = lensProg ?: return
        val fm = frameMesh ?: return
        val lm = lensMesh ?: return
        val amb = ambient.coerceIn(0.4f, 1.25f)

        // 2) opaque frame (crystal frames are translucent and go to the blended pass)
        if (!spec.isTranslucentFrame) drawFrame(fp, fm, spec, mvp, model, amb, 1f)

        // 3) blended: crystal frame first, then the lenses (depth-tested, no depth writes)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        if (spec.isTranslucentFrame) drawFrame(fp, fm, spec, mvp, model, amb, 0.6f)
        drawLenses(lp, lm, spec, mvp, model, amb)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun setCommon(p: GlProgram, mvp: FloatArray, model: FloatArray, amb: Float) {
        p.use()
        GLES20.glUniformMatrix4fv(p.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(p.uniform("uModel"), 1, false, model, 0)
        GLES20.glUniform1f(p.uniform("uAmbient"), amb)
        GLES20.glUniform3f(p.uniform("uLightDir"), -0.35f, 0.65f, 0.67f)
        GLES20.glUniform1f(p.uniform("uLensY"), EyewearGeometry.LENS_CENTER_Y)
    }

    private fun drawFrame(p: GlProgram, mesh: GpuMesh, spec: FrameSpec, mvp: FloatArray, model: FloatArray, amb: Float, alpha: Float) {
        setCommon(p, mvp, model, amb)
        val base = rgb(spec.frameColor)
        val accent = rgb(spec.accentColor)
        GLES20.glUniform3f(p.uniform("uBase"), base[0], base[1], base[2])
        GLES20.glUniform3f(p.uniform("uAccent"), accent[0], accent[1], accent[2])
        GLES20.glUniform1f(p.uniform("uPattern"), spec.pattern.id)
        GLES20.glUniform1f(p.uniform("uMetallic"), if (spec.metallic) 0.85f else 0f)
        GLES20.glUniform1f(p.uniform("uAlpha"), if (spec.pattern == Pattern.CRYSTAL) alpha else 1f)
        val tc = rgb(spec.templeColor)
        GLES20.glUniform4f(p.uniform("uTempleColor"), tc[0], tc[1], tc[2], if (spec.templeColor != 0) 1f else 0f)
        val tip = rgb(spec.tipColor)
        GLES20.glUniform4f(p.uniform("uTipColor"), tip[0], tip[1], tip[2], if (spec.tipColor != 0) 1f else 0f)
        GLES20.glUniform1f(p.uniform("uTipStart"), if (spec.tipColor != 0) spec.tipStart else 2f)
        GLES20.glUniform1f(p.uniform("uTempleMetal"), if (spec.templeMetallic) 0.85f else 0f)
        mesh.draw(p.attrib("aPos"), p.attrib("aNormal"), p.attrib("aAux"))
    }

    private fun drawLenses(p: GlProgram, mesh: GpuMesh, spec: FrameSpec, mvp: FloatArray, model: FloatArray, amb: Float) {
        setCommon(p, mvp, model, amb)
        val t = rgb(spec.tint.color)
        GLES20.glUniform3f(p.uniform("uTint"), t[0], t[1], t[2])
        GLES20.glUniform1f(p.uniform("uOpacity"), spec.tint.opacity)
        GLES20.glUniform1f(p.uniform("uMirror"), spec.tint.mirror)
        GLES20.glUniform1f(p.uniform("uGradient"), if (spec.tint.gradient) 1f else 0f)
        mesh.draw(p.attrib("aPos"), p.attrib("aNormal"), p.attrib("aAux"))
    }

    private fun rgb(argb: Int) = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f
    )

    private fun readPixels(): Bitmap? {
        return try {
            val w = viewW; val h = viewH
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buf)
            val flip = Matrix().apply { preScale(1f, -1f) } // GL rows are bottom-up
            Bitmap.createBitmap(raw, 0, 0, w, h, flip, false).also { if (it !== raw) raw.recycle() }
        } catch (e: Throwable) {
            Log.e(TAG, "capture failed", e)
            null
        }
    }

    private fun releaseGl() {
        bg?.release(); occ?.release(); frameProg?.release(); lensProg?.release(); maskProg?.release(); irisProg?.release()
        bg = null; occ = null; frameProg = null; lensProg = null; maskProg = null; irisProg = null
        frameMesh = null; lensMesh = null; occluder = null // old context is gone; nothing valid to delete
    }

    private fun fail(msg: String, e: Throwable) {
        Log.e(TAG, msg, e)
        failed = true
        onError("$msg: ${e.message ?: e.javaClass.simpleName}")
    }

    private companion object { const val TAG = "GlassesRenderer" }
}
