package com.optitry.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var glassesOverlay: GlassesOverlayView
    private var faceLandmarker: FaceLandmarker? = null
    private var cameraExecutor: ExecutorService? = null
    private var faceFound = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) status.text = "Product image selected. For accurate try-on, use a front-facing transparent PNG."
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Camera permission is required for live try-on."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 9, 11)
        window.navigationBarColor = Color.rgb(9, 9, 11)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 9, 11))
            setPadding(18, 12, 18, 14)
        }
        val heading = TextView(this).apply {
            text = "OptiTry  •  LIVE VIRTUAL EYEWEAR"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 10, 4, 12)
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        glassesOverlay = GlassesOverlayView(this)
        val cameraFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(24, 24, 27))
            addView(previewView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(glassesOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        status = TextView(this).apply {
            text = "Starting front camera and face tracking…"
            textSize = 13f
            setTextColor(Color.rgb(212, 212, 216))
            setPadding(4, 10, 4, 10)
        }
        val pick = Button(this).apply {
            text = "＋  Upload your product"
            setOnClickListener {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        val catalog = Button(this).apply {
            text = "Eyeglasses + sunglasses catalog"
            setOnClickListener {
                status.text = "Catalog scaffold has 20 eyeglasses and 20 sunglasses slots. Product overlays still need licensed image assets."
            }
        }

        root.addView(heading)
        root.addView(cameraFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(status)
        root.addView(pick)
        root.addView(catalog)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .build()
            faceLandmarker?.close()
            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            status.text = "Face model could not start: ${e.localizedMessage ?: "unknown error"}"
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            cameraExecutor?.shutdown()
            cameraExecutor = Executors.newSingleThreadExecutor()
            analysis.setAnalyzer(cameraExecutor!!) { proxy -> analyzeFrame(proxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                status.text = "Look at the camera. Glasses overlay follows your eye landmarks."
            } catch (e: Exception) {
                status.text = "Unable to start camera: ${e.localizedMessage ?: "unknown error"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // CameraX supplies YUV_420_888 frames; convert them without an extra imaging dependency.
    private fun imageProxyToBitmap(proxy: ImageProxy): android.graphics.Bitmap {
        val width = proxy.width
        val height = proxy.height
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val pixels = IntArray(width * height)
        var outputIndex = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row shr 1) * uvRowStride
            for (col in 0 until width) {
                val y = yBuffer.get(yRow + col).toInt() and 0xff
                val uvIndex = uvRow + (col shr 1) * uvPixelStride
                val u = (uBuffer.get(uvIndex).toInt() and 0xff) - 128
                val v = (vBuffer.get(uvIndex).toInt() and 0xff) - 128
                val r = (y + 1.370705f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.732446f * u).toInt().coerceIn(0, 255)
                pixels[outputIndex++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val source = imageProxyToBitmap(proxy)
            val rotation = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val upright = if (proxy.imageInfo.rotationDegrees == 0) source else
                android.graphics.Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotation, true)

            val mpImage = BitmapImageBuilder(upright).build()
            try {
                val result = faceLandmarker?.detectForVideo(mpImage, SystemClock.uptimeMillis())
                val face = result?.faceLandmarks()?.firstOrNull()
                if (face != null && face.size > 263) {
                    val points = face.map { PointF(it.x(), it.y()) }
                    runOnUiThread {
                        glassesOverlay.updateLandmarks(points)
                        if (!faceFound) status.text = "Face detected • Move your head to test live tracking."
                        faceFound = true
                    }
                } else {
                    runOnUiThread {
                        glassesOverlay.updateLandmarks(null)
                        if (faceFound) status.text = "Face not detected. Center your face in the camera."
                        faceFound = false
                    }
                }
            } finally {
                mpImage.close()
                if (upright !== source) upright.recycle()
                source.recycle()
            }
        } catch (_: Exception) {
            // A single dropped camera frame should not stop the live preview.
        } finally {
            proxy.close()
        }
    }

    override fun onDestroy() {
        cameraExecutor?.shutdown()
        faceLandmarker?.close()
        faceLandmarker = null
        super.onDestroy()
    }
}

private class GlassesOverlayView(context: android.content.Context) : View(context) {
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 211, 238)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 211, 238)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    @Volatile private var landmarks: List<PointF>? = null

    fun updateLandmarks(points: List<PointF>?) {
        landmarks = points
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val face = landmarks ?: return
        if (face.size <= 362) return

        // MediaPipe's eye corners: 33/133 and 362/263. Preview is mirrored for the front camera.
        fun point(index: Int): PointF = PointF((1f - face[index].x) * width, face[index].y * height)
        val leftEyeA = point(33)
        val leftEyeB = point(133)
        val rightEyeA = point(362)
        val rightEyeB = point(263)
        val c1 = PointF((leftEyeA.x + leftEyeB.x) / 2f, (leftEyeA.y + leftEyeB.y) / 2f)
        val c2 = PointF((rightEyeA.x + rightEyeB.x) / 2f, (rightEyeA.y + rightEyeB.y) / 2f)
        val left = if (c1.x < c2.x) c1 else c2
        val right = if (c1.x < c2.x) c2 else c1
        val eyeDistance = abs(right.x - left.x)
        if (eyeDistance < 12f) return

        val lensWidth = eyeDistance * 0.66f
        val lensHeight = max(eyeDistance * 0.38f, 14f)
        val corner = lensHeight * 0.22f
        val leftRect = RectF(left.x - lensWidth / 2f, left.y - lensHeight / 2f,
            left.x + lensWidth / 2f, left.y + lensHeight / 2f)
        val rightRect = RectF(right.x - lensWidth / 2f, right.y - lensHeight / 2f,
            right.x + lensWidth / 2f, right.y + lensHeight / 2f)
        canvas.drawRoundRect(leftRect, corner, corner, framePaint)
        canvas.drawRoundRect(rightRect, corner, corner, framePaint)
        val bridgeY = (left.y + right.y) / 2f
        canvas.drawLine(leftRect.right, bridgeY, rightRect.left, bridgeY, bridgePaint)
        canvas.drawLine(leftRect.left, left.y, leftRect.left - lensHeight * 0.28f, left.y - lensHeight * 0.05f, bridgePaint)
        canvas.drawLine(rightRect.right, right.y, rightRect.right + lensHeight * 0.28f, right.y - lensHeight * 0.05f, bridgePaint)
    }
}
