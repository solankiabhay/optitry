package com.optitry.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView

    private val imagePicker = registerForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) status.text = "Custom product selected. Transparent/cut-out frame assets work best."
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Camera permission is required for live try-on."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 9, 11)
        window.navigationBarColor = Color.rgb(9, 9, 11)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(9, 9, 11)); setPadding(18, 12, 18, 14) }
        val heading = TextView(this).apply { text = "OptiTry  •  VIRTUAL EYEWEAR"; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; setPadding(4, 10, 4, 12) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        val previewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(24, 24, 27)); addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
        status = TextView(this).apply { text = "Position your face in the camera. Live face tracking is not yet integrated."; textSize = 13f; setTextColor(Color.rgb(212, 212, 216)); setPadding(4, 10, 4, 10) }
        val pick = Button(this).apply { text = "＋  Upload your product"; setOnClickListener { imagePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) } }
        val catalog = Button(this).apply { text = "Browse 40 sample frame slots"; setOnClickListener { status.text = "Catalog scaffold: 20 eyeglasses + 20 sunglasses. Add licensed transparent PNG/WebP assets under app/src/main/assets/products/." } }
        root.addView(heading)
        root.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status); root.addView(pick); root.addView(catalog)
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                status.text = "Camera ready. Face-landmark model and glasses overlay are next."
            } catch (e: Exception) { status.text = "Unable to start camera: ${e.localizedMessage ?: "unknown error"}" }
        }, ContextCompat.getMainExecutor(this))
    }
}
