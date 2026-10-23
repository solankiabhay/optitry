package com.optitry.app.render

import android.graphics.Bitmap
import com.optitry.app.core.Eyes
import com.optitry.app.core.FacePose

/** One camera/photo frame plus the tracking result computed from exactly that frame. */
class FrameSlot internal constructor() {
    var bitmap: Bitmap? = null
        internal set
    @JvmField var pose: FacePose? = null
    @JvmField var eyes: Eyes? = null
    @JvmField var ambient: Float = 1f
    @JvmField var mirror: Boolean = true
}

/**
 * Three reusable frame buffers so the analysis thread never allocates per frame and never writes
 * into a bitmap the GL thread is uploading. The video and the tracked pose travel together, so the
 * glasses are always drawn on the very frame they were tracked on (no video/overlay lag).
 */
class FrameRing {
    private val slots = Array(3) { FrameSlot() }
    private var pending: FrameSlot? = null
    private var reading: FrameSlot? = null

    /** Returns a free slot whose bitmap is exactly [w] x [h]. Call from the producer thread only. */
    @Synchronized
    fun obtain(w: Int, h: Int): FrameSlot {
        val slot = slots.first { it !== pending && it !== reading }
        val bmp = slot.bitmap
        if (bmp == null || bmp.isRecycled || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            slot.bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        slot.pose = null
        slot.eyes = null
        return slot
    }

    @Synchronized fun publish(slot: FrameSlot) { pending = slot }

    /** GL thread: newest published frame, or null if nothing new. Must be followed by [release]. */
    @Synchronized
    fun takeLatest(): FrameSlot? {
        val p = pending ?: return null
        pending = null
        reading = p
        return p
    }

    @Synchronized fun release() { reading = null }

    @Synchronized
    fun clear() {
        pending = null
        reading = null
    }
}
