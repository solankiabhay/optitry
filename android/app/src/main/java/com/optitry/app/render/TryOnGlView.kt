package com.optitry.app.render

import android.content.Context
import android.opengl.GLSurfaceView

/** Full-screen GL surface that renders on demand (one render per published camera frame). */
class TryOnGlView(context: Context, val renderer: GlassesRenderer) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.requestRender = { requestRender() }
    }
}
