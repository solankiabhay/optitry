package com.optitry.app.tracking

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.optitry.app.core.FacePose
import com.optitry.app.core.PoseSmoother

/**
 * MediaPipe Face Landmarker wrapper that returns the rigid head pose from the model's facial
 * transformation matrix (not just 2D landmarks), so glasses can be placed and rotated in 3D.
 * Video mode tracks across frames and smooths with One Euro filters; still mode handles gallery photos.
 */
class FaceTracker(private val context: Context) : AutoCloseable {
    private var video: FaceLandmarker? = null
    private var still: FaceLandmarker? = null
    private val smoother = PoseSmoother()
    private var lastGood: FacePose? = null
    private var lastGoodNanos = 0L
    private var lastTimestampMs = -1L

    private fun options(mode: RunningMode): FaceLandmarker.FaceLandmarkerOptions =
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(mode)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(true)
            .build()

    /** Loads the model. Throws if the model asset is missing or MediaPipe cannot initialise. */
    @Synchronized
    fun prepareVideo() {
        if (video == null) video = FaceLandmarker.createFromOptions(context, options(RunningMode.VIDEO))
    }

    @Synchronized
    fun prepareStill() {
        if (still == null) still = FaceLandmarker.createFromOptions(context, options(RunningMode.IMAGE))
    }

    /** Resets temporal state (call when the camera restarts). */
    @Synchronized
    fun resetTracking() {
        smoother.reset()
        lastGood = null
        lastTimestampMs = -1L
    }

    /** [bitmap] must be upright. Returns the smoothed pose, or null if no face (after a short grace period). */
    @Synchronized
    fun trackVideo(bitmap: Bitmap, timestampNanos: Long): FacePose? {
        val landmarker = video ?: return null
        var ts = timestampNanos / 1_000_000L
        if (ts <= lastTimestampMs) ts = lastTimestampMs + 1 // MediaPipe requires strictly increasing timestamps
        lastTimestampMs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val raw = poseOf(result)
        if (raw != null) {
            val smoothed = smoother.update(raw, timestampNanos)
            lastGood = smoothed
            lastGoodNanos = timestampNanos
            return smoothed
        }
        val g = lastGood
        if (g != null && timestampNanos - lastGoodNanos < GRACE_NANOS) return g // ride out single dropped frames
        smoother.reset()
        lastGood = null
        return null
    }

    @Synchronized
    fun detectStill(bitmap: Bitmap): FacePose? {
        val landmarker = still ?: return null
        return poseOf(landmarker.detect(BitmapImageBuilder(bitmap).build()))
    }

    private fun poseOf(result: FaceLandmarkerResult): FacePose? {
        val matrices = result.facialTransformationMatrixes()
        if (!matrices.isPresent || matrices.get().isEmpty()) return null
        return FacePose.fromMatrix(matrices.get()[0])
    }

    @Synchronized
    override fun close() {
        video?.close(); video = null
        still?.close(); still = null
    }

    companion object {
        const val MODEL_ASSET = "face_landmarker.task"
        private const val GRACE_NANOS = 150_000_000L
    }
}
