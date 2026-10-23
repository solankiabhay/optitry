package com.optitry.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.optitry.app.render.GlassesRenderer
import com.optitry.app.tracking.FaceTracker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Front camera -> upright bitmap -> face tracking -> renderer, all on one analysis thread, so the
 * displayed frame and the head pose always come from the same camera frame.
 */
class CameraController(
    private val activity: AppCompatActivity,
    private val renderer: GlassesRenderer,
    private val tracker: FaceTracker,
    private val listener: Listener
) {
    interface Listener {
        fun onFirstFrame()
        fun onFaceStateChanged(found: Boolean)
        fun onCameraError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    @Volatile private var running = false
    @Volatile private var errorReported = false
    private var srcBitmap: Bitmap? = null
    private val matrix = Matrix()
    private var ambientSmoothed = 1f
    private var firstFrameSent = false
    private var lastFaceFound: Boolean? = null
    private var canvasBitmap: Bitmap? = null
    private var canvas: Canvas? = null

    fun start() {
        running = true
        errorReported = false
        firstFrameSent = false
        lastFaceFound = null
        tracker.resetTracking()
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                Log.e(TAG, "camera provider failed", e)
                listener.onCameraError("Could not open the camera: ${e.message ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        running = false
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        analysis = null
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    /** Call after a configuration change (rotation) so frames keep coming in upright. */
    fun updateRotation() {
        analysis?.targetRotation = activity.window.decorView.display?.rotation ?: Surface.ROTATION_0
    }

    private fun bind() {
        val p = provider ?: return
        val front = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val selector = when {
            front -> CameraSelector.DEFAULT_FRONT_CAMERA
            p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> {
                listener.onCameraError("No camera found on this device.")
                return
            }
        }
        val mirror = front // selfie view is mirrored, like a real mirror
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        val useCase = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        useCase.setAnalyzer(executor) { image -> analyze(image, mirror) }
        try {
            p.unbindAll()
            p.bindToLifecycle(activity, selector, useCase)
            analysis = useCase
            updateRotation()
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            listener.onCameraError("Could not start the camera: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun analyze(image: ImageProxy, mirror: Boolean) {
        try {
            if (!running) return
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val src: Bitmap = if (plane.rowStride == w * 4) {
                val b = srcBitmap?.takeIf { it.width == w && it.height == h && !it.isRecycled }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { srcBitmap = it }
                plane.buffer.rewind()
                b.copyPixelsFromBuffer(plane.buffer)
                b
            } else {
                image.toBitmap() // padded rows: let CameraX handle the copy
            }

            val rotation = image.imageInfo.rotationDegrees
            val swap = rotation % 180 != 0
            val dw = if (swap) h else w
            val dh = if (swap) w else h
            val slot = renderer.ring.obtain(dw, dh)
            val dst = slot.bitmap ?: return
            if (canvasBitmap !== dst) {
                canvasBitmap = dst
                canvas = Canvas(dst)
            }
            matrix.reset()
            matrix.postTranslate(-w / 2f, -h / 2f)
            matrix.postRotate(rotation.toFloat())
            matrix.postTranslate(dw / 2f, dh / 2f)
            canvas!!.drawBitmap(src, matrix, null)

            val tracked = tracker.trackVideo(dst, image.imageInfo.timestamp)
            val pose = tracked?.pose
            slot.pose = pose
            slot.eyes = tracked?.eyes
            slot.mirror = mirror
            ambientSmoothed += (estimateAmbient(dst) - ambientSmoothed) * 0.08f
            slot.ambient = ambientSmoothed
            renderer.ring.publish(slot)
            renderer.requestRender()

            if (!firstFrameSent) {
                firstFrameSent = true
                main.post { listener.onFirstFrame() }
            }
            val found = pose != null
            if (lastFaceFound != found) {
                lastFaceFound = found
                main.post { listener.onFaceStateChanged(found) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "frame analysis failed", e)
            if (!errorReported) {
                errorReported = true
                main.post { listener.onCameraError("Face tracking failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraController"

        /** Average scene brightness -> ambient light multiplier for the glasses shaders. */
        fun estimateAmbient(bmp: Bitmap): Float {
            var sum = 0f
            var n = 0
            val steps = 12
            for (iy in 1 until steps) for (ix in 1 until steps) {
                val px = bmp.getPixel(bmp.width * ix / steps, bmp.height * iy / steps)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                n++
            }
            val luma = if (n == 0) 0.6f else sum / n
            return (0.55f + 0.65f * luma).coerceIn(0.5f, 1.2f)
        }
    }
}
