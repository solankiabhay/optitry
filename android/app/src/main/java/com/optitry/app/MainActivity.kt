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
import android.provider.MediaStore
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import kotlin.math.min
import kotlin.math.pow

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var glassesOverlay: GlassesOverlayView
    private lateinit var cameraFrame: FrameLayout
    private lateinit var photoView: ImageView
    private var selectedPhotoBitmap: android.graphics.Bitmap? = null
    @Volatile private var photoMode = false
    private var faceLandmarker: FaceLandmarker? = null
    private var cameraExecutor: ExecutorService? = null
    private var faceFound = false
    private var sunglassesMode = false
    internal data class FrameStyle(val name: String, val color: Int, val sunglasses: Boolean, val round: Boolean)
    private val opticalStyles = listOf(
        FrameStyle("Classic Black", Color.rgb(35,35,39), false, false), FrameStyle("Crystal Clear", Color.rgb(160,210,218), false, true),
        FrameStyle("Tortoise", Color.rgb(145,83,42), false, false), FrameStyle("Silver Metal", Color.rgb(190,198,205), false, true),
        FrameStyle("Rose Gold", Color.rgb(205,135,125), false, true), FrameStyle("Navy Blue", Color.rgb(40,80,140), false, false),
        FrameStyle("Forest Green", Color.rgb(42,112,83), false, true), FrameStyle("Burgundy", Color.rgb(120,35,60), false, false),
        FrameStyle("Matte Grey", Color.rgb(100,105,115), false, false), FrameStyle("Champagne", Color.rgb(190,160,110), false, true),
        FrameStyle("Retro Round", Color.rgb(65,65,70), false, true), FrameStyle("Bold Square", Color.rgb(25,25,28), false, false),
        FrameStyle("Slim Gold", Color.rgb(205,170,90), false, true), FrameStyle("Transparent Blue", Color.rgb(95,165,205), false, false),
        FrameStyle("Olive", Color.rgb(105,115,60), false, false), FrameStyle("Plum", Color.rgb(105,60,110), false, true),
        FrameStyle("Copper", Color.rgb(170,105,70), false, false), FrameStyle("White Frame", Color.rgb(225,225,220), false, false),
        FrameStyle("Teal", Color.rgb(35,135,140), false, true), FrameStyle("Aviator Optical", Color.rgb(185,150,85), false, true)
    )
    private val sunStyles = listOf(
        FrameStyle("Black Classic", Color.rgb(25,25,28), true, false), FrameStyle("Aviator Gold", Color.rgb(200,165,85), true, true),
        FrameStyle("Tortoise Sun", Color.rgb(125,70,35), true, false), FrameStyle("Silver Mirror", Color.rgb(175,190,205), true, true),
        FrameStyle("Rose Tint", Color.rgb(190,105,125), true, true), FrameStyle("Ocean Blue", Color.rgb(35,100,175), true, false),
        FrameStyle("Emerald", Color.rgb(35,120,85), true, true), FrameStyle("Burgundy Sun", Color.rgb(105,30,50), true, false),
        FrameStyle("Smoke Grey", Color.rgb(90,95,105), true, false), FrameStyle("Champagne Sun", Color.rgb(185,150,95), true, true),
        FrameStyle("Round Retro", Color.rgb(40,40,45), true, true), FrameStyle("Sport Square", Color.rgb(20,20,25), true, false),
        FrameStyle("Gold Wire", Color.rgb(205,170,95), true, true), FrameStyle("Ice Blue", Color.rgb(100,170,210), true, false),
        FrameStyle("Olive Pilot", Color.rgb(100,110,55), true, false), FrameStyle("Plum Fade", Color.rgb(100,55,105), true, true),
        FrameStyle("Copper Lens", Color.rgb(165,95,60), true, false), FrameStyle("White Sport", Color.rgb(220,220,215), true, false),
        FrameStyle("Teal Mirror", Color.rgb(30,130,135), true, true), FrameStyle("Retro Cat Eye", Color.rgb(30,30,35), true, false)
    )

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) showPhotoTryOn(uri)
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
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setBackgroundColor(Color.rgb(24, 24, 27))
        }
        cameraFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(24, 24, 27))
            addView(photoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            text = "＋  Try on your photo"
            setOnClickListener {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        val categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val catalogPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val opticalButton = Button(this).apply {
            text = "Eyeglasses (20)"
            setOnClickListener { sunglassesMode = false; renderCatalog(catalogPanel) }
        }
        val sunButton = Button(this).apply {
            text = "Sunglasses (20)"
            setOnClickListener { sunglassesMode = true; renderCatalog(catalogPanel) }
        }
        categoryRow.addView(opticalButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        categoryRow.addView(sunButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        renderCatalog(catalogPanel)

        root.addView(heading)
        root.addView(cameraFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(status)
        root.addView(pick)
        val liveCameraButton = Button(this).apply {
            text = "Back to live camera"
            setOnClickListener {
                photoMode = false
                glassesOverlay.setPhotoMode(false)
                photoView.visibility = View.GONE
                previewView.visibility = View.VISIBLE
                glassesOverlay.updateLandmarks(null)
                faceFound = false
                status.text = "Live camera mode • look at the camera to preview frames."
            }
        }
        root.addView(liveCameraButton)
        val saveButton = Button(this).apply {
            text = "Save try-on photo"
            setOnClickListener { saveTryOnPhoto() }
        }
        root.addView(saveButton)
        root.addView(categoryRow)
        root.addView(catalogPanel)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPhotoTryOn(uri: android.net.Uri) {
        try {
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
                    .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            }
            selectedPhotoBitmap?.recycle()
            selectedPhotoBitmap = bitmap
            photoView.setImageBitmap(bitmap)
            photoMode = true
            glassesOverlay.setPhotoMode(true, bitmap.width, bitmap.height)
            previewView.visibility = View.GONE
            photoView.visibility = View.VISIBLE
            status.text = "Photo loaded • detecting face for try-on…"
            Thread {
                try {
                    val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                        .setRunningMode(RunningMode.IMAGE).setNumFaces(1).build()
                    val detector = FaceLandmarker.createFromOptions(this, options)
                    val result = detector.detect(BitmapImageBuilder(bitmap).build())
                    val face = result.faceLandmarks().firstOrNull()
                    detector.close()
                    runOnUiThread {
                        if (!photoMode || selectedPhotoBitmap !== bitmap) return@runOnUiThread
                        if (face != null) {
                            glassesOverlay.updateLandmarks(face.map { PointF(it.x(), it.y()) })
                            faceFound = true
                            status.text = "Photo face detected • choose a frame and save your try-on."
                        } else {
                            glassesOverlay.updateLandmarks(null)
                            faceFound = false
                            status.text = "No face found. Try a clear, front-facing portrait."
                        }
                    }
                } catch (e: Exception) { runOnUiThread { status.text = "Photo face detection failed: ${e.localizedMessage ?: "try another photo"}" } }
            }.start()
        } catch (e: Exception) { status.text = "Could not open photo: ${e.localizedMessage ?: "unsupported image"}" }
    }

    private fun saveTryOnPhoto() {
        if (!faceFound) {
            status.text = "Face not detected yet. Center your face before saving."
            return
        }
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(
                cameraFrame.width, cameraFrame.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            cameraFrame.draw(canvas)
            val saved = MediaStore.Images.Media.insertImage(
                contentResolver, bitmap, "OptiTry-${System.currentTimeMillis()}", "OptiTry virtual eyewear preview"
            )
            bitmap.recycle()
            status.text = if (saved != null) "Try-on photo saved to Gallery." else "Could not save photo. Please try again."
        } catch (e: Exception) {
            status.text = "Could not save photo: ${e.localizedMessage ?: "unknown error"}"
        }
    }

    private fun renderCatalog(panel: LinearLayout) {
        panel.removeAllViews()
        panel.addView(TextView(this).apply {
            text = if (sunglassesMode) "SUNGLASSES • Choose a style" else "EYEGLASSES • Choose a style"
            textSize = 12f
            setTextColor(Color.rgb(165,243,252))
            setPadding(4,4,4,4)
        })
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val items = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val styles = if (sunglassesMode) sunStyles else opticalStyles
        styles.forEachIndexed { index, style ->
            val item = Button(this).apply {
                text = "%02d  %s".format(index + 1, style.name)
                textSize = 11f
                setOnClickListener {
                    glassesOverlay.setFrameStyle(style)
                    status.text = style.name + " selected • Look at the camera to preview the frame."
                }
            }
            items.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 6 })
        }
        scroll.addView(items)
        panel.addView(scroll)
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
                val result = if (photoMode) null else faceLandmarker?.detectForVideo(mpImage, SystemClock.uptimeMillis())
                val face = result?.faceLandmarks()?.firstOrNull()
                if (face != null && face.size > 263) {
                    val points = face.map { PointF(it.x(), it.y()) }
                    runOnUiThread {
                        if (photoMode) return@runOnUiThread
                        glassesOverlay.updateLandmarks(points, upright.width, upright.height)
                        if (!faceFound) status.text = "Face detected • Move your head to test live tracking."
                        faceFound = true
                    }
                } else {
                    runOnUiThread {
                        if (photoMode) return@runOnUiThread
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
        selectedPhotoBitmap?.recycle()
        faceLandmarker?.close()
        faceLandmarker = null
        super.onDestroy()
    }
}

private class GlassesOverlayView(context: android.content.Context) : View(context) {
    private var selectedStyle = MainActivity.FrameStyle("Classic Black", Color.rgb(35,35,39), false, false)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = selectedStyle.color; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(24,150,190,210); style = Paint.Style.FILL }
    private val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = selectedStyle.color; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }

    fun setFrameStyle(style: MainActivity.FrameStyle) {
        selectedStyle = style
        framePaint.color = style.color
        bridgePaint.color = style.color
        lensPaint.color = if (style.sunglasses) Color.argb(145,24,34,48) else Color.argb(24,150,190,210)
        framePaint.strokeWidth = if (style.sunglasses) 7f else 5f
        postInvalidate()
    }
    @Volatile private var landmarks: List<PointF>? = null
    @Volatile private var photoMode = false
    @Volatile private var photoBitmapWidth = 0
    @Volatile private var photoBitmapHeight = 0
    @Volatile private var cameraImageWidth = 0
    @Volatile private var cameraImageHeight = 0
    private var smoothedLandmarks: List<PointF>? = null
    fun setPhotoMode(enabled: Boolean, bitmapWidth: Int = 0, bitmapHeight: Int = 0) {
        photoMode = enabled
        photoBitmapWidth = bitmapWidth
        photoBitmapHeight = bitmapHeight
        postInvalidate()
    }

    fun updateLandmarks(points: List<PointF>?, imageWidth: Int = 0, imageHeight: Int = 0) {
        if (imageWidth > 0 && imageHeight > 0) {
            cameraImageWidth = imageWidth
            cameraImageHeight = imageHeight
        }
        // Smooth small frame-to-frame detector noise so the glasses do not visibly jitter.
        if (points == null) {
            smoothedLandmarks = null
            landmarks = null
        } else if (photoMode || smoothedLandmarks == null || smoothedLandmarks!!.size != points.size) {
            smoothedLandmarks = points.map { PointF(it.x, it.y) }
            landmarks = smoothedLandmarks
        } else {
            val previous = smoothedLandmarks!!
            val alpha = if (photoMode) 1f else 0.38f
            val smooth = points.mapIndexed { index, p ->
                PointF(previous[index].x + (p.x - previous[index].x) * alpha,
                       previous[index].y + (p.y - previous[index].y) * alpha)
            }
            smoothedLandmarks = smooth
            landmarks = smooth
        }
        postInvalidate()
    }

    private fun lensPath(rect: RectF, style: MainActivity.FrameStyle, inset: Float): android.graphics.Path {
        val r = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        val path = android.graphics.Path()
        val shape = style.name.lowercase()
        when {
            style.round || shape.contains("round") || shape.contains("circle") -> {
                path.addOval(r, android.graphics.Path.Direction.CW)
            }
            shape.contains("aviator") || shape.contains("pilot") -> {
                path.moveTo(r.left + r.width() * 0.08f, r.top + r.height() * 0.08f)
                path.quadTo(r.centerX(), r.top - r.height() * 0.02f, r.right - r.width() * 0.08f, r.top + r.height() * 0.08f)
                path.lineTo(r.right, r.top + r.height() * 0.48f)
                path.quadTo(r.centerX(), r.bottom + r.height() * 0.12f, r.left, r.top + r.height() * 0.48f)
                path.close()
            }
            shape.contains("cat") -> {
                path.moveTo(r.left, r.top + r.height() * 0.12f)
                path.lineTo(r.left + r.width() * 0.28f, r.top + r.height() * 0.02f)
                path.quadTo(r.centerX(), r.top + r.height() * 0.12f, r.right, r.top - r.height() * 0.02f)
                path.lineTo(r.right - r.width() * 0.06f, r.bottom - r.height() * 0.18f)
                path.quadTo(r.centerX(), r.bottom + r.height() * 0.04f, r.left + r.width() * 0.08f, r.bottom - r.height() * 0.12f)
                path.close()
            }
            shape.contains("sport") || shape.contains("square") || shape.contains("classic") || shape.contains("black") -> {
                val radius = min(r.width() * 0.12f, r.height() * 0.16f)
                path.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
            }
            else -> {
                val radius = min(r.width() * 0.24f, r.height() * 0.28f)
                path.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
            }
        }
        return path
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val face = landmarks ?: return
        if (face.size <= 362) return

        fun point(index: Int): PointF {
            if (photoMode) {
                val bitmapWidth = photoBitmapWidth
                val bitmapHeight = photoBitmapHeight
                if (bitmapWidth > 0 && bitmapHeight > 0) {
                    val scale = min(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight)
                    val shownWidth = bitmapWidth * scale
                    val shownHeight = bitmapHeight * scale
                    val offsetX = (width - shownWidth) / 2f
                    val offsetY = (height - shownHeight) / 2f
                    return PointF(offsetX + face[index].x * shownWidth, offsetY + face[index].y * shownHeight)
                }
            }
            // Match PreviewView's FILL_CENTER crop; the analyzer image and visible camera
            // preview do not always have the same aspect ratio.
            val imageWidth = cameraImageWidth
            val imageHeight = cameraImageHeight
            if (imageWidth > 0 && imageHeight > 0) {
                val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
                val shownWidth = imageWidth * scale
                val shownHeight = imageHeight * scale
                val offsetX = (width - shownWidth) / 2f
                val offsetY = (height - shownHeight) / 2f
                return PointF(offsetX + (1f - face[index].x) * shownWidth, offsetY + face[index].y * shownHeight)
            }
            return PointF((1f - face[index].x) * width, face[index].y * height)
        }

        val eyeA = point(33)
        val eyeB = point(133)
        val eyeC = point(362)
        val eyeD = point(263)
        val centerA = PointF((eyeA.x + eyeB.x) / 2f, (eyeA.y + eyeB.y) / 2f)
        val centerB = PointF((eyeC.x + eyeD.x) / 2f, (eyeC.y + eyeD.y) / 2f)
        val left = if (centerA.x < centerB.x) centerA else centerB
        val right = if (centerA.x < centerB.x) centerB else centerA
        val eyeDistance = abs(right.x - left.x)
        if (eyeDistance < 12f) return

        val centerX = (left.x + right.x) / 2f
        val centerY = (left.y + right.y) / 2f
        val eyeRoll = kotlin.math.atan2((right.y - left.y).toDouble(), (right.x - left.x).toDouble()).toFloat()
        val nose = point(1.coerceAtMost(face.lastIndex))
        // Approximate head pose from stable facial landmarks: nose offset estimates yaw,
        // nose-to-eye vertical offset estimates pitch, and the eye line gives roll.
        val yaw = (((nose.x - centerX) / eyeDistance) * 1.65f).coerceIn(-0.78f, 0.78f)
        val pitch = ((((nose.y - centerY) / eyeDistance) - 0.17f) * 1.25f).coerceIn(-0.42f, 0.42f)
        // Estimate the temple-to-temple span from face mesh landmarks and size the
        // frame to the face, while clamping bad/partial detections to a safe range.
        val templeSpan = abs(point(356).x - point(127).x)
            .coerceIn(eyeDistance * 1.85f, eyeDistance * 2.85f)
        val targetOuterWidth = templeSpan * 0.84f
        val lensGap = eyeDistance * 0.16f
        val lensWidth = max((targetOuterWidth - lensGap) * 0.5f, eyeDistance * 0.72f)
        // Slightly taller lenses and a measured bridge gap look less like goggles.
        val lensHeight = max(eyeDistance * 0.57f, 18f)
        val lensCenterOffset = lensGap * 0.5f + lensWidth * 0.5f
        val frameDepth = max(eyeDistance * 0.052f, 2.3f)
        val focalLength = eyeDistance * 4.2f

        data class P3(val x: Float, val y: Float, val z: Float)
        fun project(p: P3): PointF {
            val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw)
            val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
            val cr = kotlin.math.cos(eyeRoll); val sr = kotlin.math.sin(eyeRoll)
            val x1 = p.x * cy + p.z * sy
            val z1 = -p.x * sy + p.z * cy
            val y1 = p.y * cp - z1 * sp
            val z2 = p.y * sp + z1 * cp
            val x2 = x1 * cr - y1 * sr
            val y2 = x1 * sr + y1 * cr
            val perspective = focalLength / (focalLength + z2).coerceAtLeast(focalLength * 0.45f)
            return PointF(centerX + x2 * perspective, centerY + y2 * perspective)
        }

        fun outline(cx: Float, cy: Float, z: Float, scale: Float = 1f): List<P3> {
            val shape = selectedStyle.name.lowercase()
            val points = ArrayList<P3>(48)
            for (i in 0 until 48) {
                val t = (2.0 * Math.PI * i / 48.0)
                val c = kotlin.math.cos(t).toFloat()
                val s = kotlin.math.sin(t).toFloat()
                var nx = c
                var ny = s
                when {
                    selectedStyle.round || shape.contains("round") || shape.contains("circle") -> {
                        nx = c; ny = s
                    }
                    shape.contains("aviator") || shape.contains("pilot") -> {
                        nx = c * (1f - 0.12f * s)
                        ny = s * (if (s < 0f) 0.92f else 1.12f)
                    }
                    shape.contains("cat") -> {
                        nx = c
                        ny = s * (1f - 0.12f * c) - 0.06f * c
                    }
                    shape.contains("square") || shape.contains("sport") || shape.contains("classic") || shape.contains("black") -> {
                        val exponent = 0.45f
                        nx = kotlin.math.sign(c) * kotlin.math.abs(c).let { it.pow(exponent) }
                        ny = kotlin.math.sign(s) * kotlin.math.abs(s).let { it.pow(exponent) }
                    }
                }
                points.add(P3(cx + nx * lensWidth * 0.5f * scale, cy + ny * lensHeight * 0.5f * scale, z))
            }
            return points
        }

        fun path3(points: List<P3>, close: Boolean = true): android.graphics.Path {
            val path = android.graphics.Path()
            points.forEachIndexed { index, p ->
                val q = project(p)
                if (index == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
            }
            if (close) path.close()
            return path
        }

        val frameColor = selectedStyle.color
        val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, (Color.red(frameColor) * 0.58f).toInt(), (Color.green(frameColor) * 0.58f).toInt(), (Color.blue(frameColor) * 0.58f).toInt())
            style = Paint.Style.STROKE
            strokeWidth = max(2f, eyeDistance * 0.035f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameColor
            style = Paint.Style.STROKE
            strokeWidth = if (selectedStyle.sunglasses) max(eyeDistance * 0.045f, 4f) else max(eyeDistance * 0.031f, 2.7f)
            strokeJoin = Paint.Join.ROUND
        }
        val lensFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (selectedStyle.sunglasses) Color.argb(168, 25, 36, 52) else Color.argb(22, 150, 205, 220)
            style = Paint.Style.FILL
        }
        val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(155, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, eyeDistance * 0.009f)
        }

        val leftCx = -lensCenterOffset
        val rightCx = lensCenterOffset
        val lensY = 0f
        // Draw the rear rim first, then the lens surface and the raised front rim.
        for (cx in listOf(leftCx, rightCx)) {
            val rear = outline(cx, lensY, -frameDepth)
            val front = outline(cx, lensY, frameDepth)
            val rearProjected = rear.map { project(it) }
            val frontProjected = front.map { project(it) }
            for (i in rear.indices step 3) {
                val j = (i + 1) % rear.size
                canvas.drawLine(rearProjected[i].x, rearProjected[i].y, frontProjected[i].x, frontProjected[i].y, sidePaint)
            }
            if (selectedStyle.sunglasses) canvas.drawPath(path3(outline(cx, lensY, frameDepth * 0.7f, 0.94f)), lensFill)
            else canvas.drawPath(path3(outline(cx, lensY, frameDepth * 0.7f, 0.94f)), lensFill)
            canvas.drawPath(path3(front), rimPaint)
            canvas.drawPath(path3(outline(cx, lensY, frameDepth + 0.5f, 0.87f)), bevelPaint)
        }

        // Nose bridge is a shallow 3D arch, not a flat horizontal connector.
        val bridge = listOf(
            P3(-lensCenterOffset + lensWidth * 0.42f, -lensHeight * 0.02f, frameDepth),
            P3(-eyeDistance * 0.12f, -lensHeight * 0.10f, frameDepth * 1.4f),
            P3(eyeDistance * 0.12f, -lensHeight * 0.10f, frameDepth * 1.4f),
            P3(lensCenterOffset - lensWidth * 0.42f, -lensHeight * 0.02f, frameDepth)
        )
        val bridgePath = android.graphics.Path()
        bridge.forEachIndexed { index, p ->
            val q = project(p)
            if (index == 0) bridgePath.moveTo(q.x, q.y) else bridgePath.lineTo(q.x, q.y)
        }
        val bridgeStroke = Paint(rimPaint).apply { style = Paint.Style.STROKE; strokeWidth = max(eyeDistance * 0.035f, 3f); strokeCap = Paint.Cap.ROUND }
        canvas.drawPath(bridgePath, bridgeStroke)

        // Temple arms extend backwards in depth and project toward the ears.
        val templeStartY = lensHeight * 0.02f
        val templeEndZ = -eyeDistance * 0.72f
        val templePaths = listOf(
            listOf(P3(-lensCenterOffset - lensWidth * 0.48f, templeStartY, frameDepth), P3(-lensCenterOffset - lensWidth * 0.68f, templeStartY + lensHeight * 0.04f, -eyeDistance * 0.18f), P3(-lensCenterOffset - lensWidth * 0.82f, templeStartY + lensHeight * 0.10f, templeEndZ)),
            listOf(P3(lensCenterOffset + lensWidth * 0.48f, templeStartY, frameDepth), P3(lensCenterOffset + lensWidth * 0.68f, templeStartY + lensHeight * 0.04f, -eyeDistance * 0.18f), P3(lensCenterOffset + lensWidth * 0.82f, templeStartY + lensHeight * 0.10f, templeEndZ))
        )
        templePaths.forEach { points ->
            val projected = points.map { project(it) }
            for (i in 0 until projected.size - 1) {
                canvas.drawLine(projected[i].x, projected[i].y, projected[i + 1].x, projected[i + 1].y, bridgeStroke)
            }
        }

        if (selectedStyle.sunglasses) {
            val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(48, 220, 240, 255)
                style = Paint.Style.STROKE
                strokeWidth = max(eyeDistance * 0.018f, 1.5f)
                strokeCap = Paint.Cap.ROUND
            }
            for (cx in listOf(leftCx, rightCx)) {
                val a = project(P3(cx - lensWidth * 0.20f, -lensHeight * 0.28f, frameDepth * 0.8f))
                val b = project(P3(cx - lensWidth * 0.02f, lensHeight * 0.18f, frameDepth * 0.8f))
                canvas.drawLine(a.x, a.y, b.x, b.y, shinePaint)
            }
        }
    }
}
