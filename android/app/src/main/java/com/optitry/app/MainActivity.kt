package com.optitry.app

import android.Manifest
import android.content.ContentValues
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.optitry.app.camera.CameraController
import com.optitry.app.core.ContactLens
import com.optitry.app.core.ContactLenses
import com.optitry.app.eyewear.Category
import com.optitry.app.eyewear.FrameCatalog
import com.optitry.app.eyewear.FrameSpec
import com.optitry.app.render.GlassesRenderer
import com.optitry.app.render.TryOnGlView
import com.optitry.app.tracking.FaceTracker
import com.optitry.app.ui.FrameAdapter
import com.optitry.app.ui.LensAdapter
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), CameraController.Listener {

    private enum class Mode { LIVE, PHOTO }
    private enum class Tab { EYEGLASSES, SUNGLASSES, LENSES }

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var renderer: GlassesRenderer
    private lateinit var glView: TryOnGlView
    private lateinit var tracker: FaceTracker
    private lateinit var camera: CameraController

    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var chip: TextView
    private lateinit var panel: LinearLayout
    private lateinit var nameView: TextView
    private lateinit var subView: TextView
    private lateinit var tabEyeglasses: TextView
    private lateinit var tabSunglasses: TextView
    private lateinit var tabLenses: TextView
    private lateinit var intensityRow: LinearLayout
    private lateinit var intensitySeek: SeekBar
    private lateinit var intensityValue: TextView
    private lateinit var lensAdapter: LensAdapter
    private lateinit var list: RecyclerView
    private lateinit var adapter: FrameAdapter
    private lateinit var backToLive: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var overlaySpinner: ProgressBar
    private lateinit var overlayTitle: TextView
    private lateinit var overlayMessage: TextView
    private lateinit var overlayPrimary: Button
    private lateinit var overlaySecondary: Button

    private var mode = Mode.LIVE
    private var category = Category.EYEGLASSES
    private var tab = Tab.EYEGLASSES
    private var selectedLens: ContactLens? = null
    private var lensOpacity = 0.72f
    private val selection = HashMap<Category, FrameSpec>()
    private var selected: FrameSpec = FrameCatalog.eyeglasses.first()
    private var pendingSave: Bitmap? = null
    private var firstFrameTimeout: Runnable? = null

    // ---- activity-result launchers -------------------------------------------------------------------

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLive() else showPermissionNeeded()
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bmp = pendingSave
        pendingSave = null
        if (granted && bmp != null) saveBitmapAsync(bmp) else toast(getString(R.string.save_failed))
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) enterPhotoMode(uri)
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FRAME, null)
        FrameCatalog.all.firstOrNull { it.id == saved }?.let { selected = it; category = it.category }
        selection[Category.EYEGLASSES] = if (selected.category == Category.EYEGLASSES) selected else FrameCatalog.eyeglasses.first()
        selection[Category.SUNGLASSES] = if (selected.category == Category.SUNGLASSES) selected else FrameCatalog.sunglasses.first()

        renderer = GlassesRenderer(applicationContext) { msg -> main.post { showError(msg, retry = false) } }
        glView = TryOnGlView(this, renderer)
        tracker = FaceTracker(applicationContext)
        camera = CameraController(this, renderer, tracker, this)

        buildUi()
        setContentView(root)
        renderer.setSpec(selected)
        val savedLens = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LENS, null)
        selectedLens = ContactLenses.all.firstOrNull { it.id == savedLens }
        selectedLens?.let { lensOpacity = it.defaultOpacity }
        lensAdapter.select(selectedLens)
        setTab(if (category == Category.SUNGLASSES) Tab.SUNGLASSES else Tab.EYEGLASSES)

        if (hasCameraPermission()) startLive() else showPermissionIntro()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (mode == Mode.LIVE && hasCameraPermission() && overlay.visibility != View.VISIBLE) camera.start()
    }

    override fun onPause() {
        camera.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        firstFrameTimeout?.let { main.removeCallbacks(it) }
        camera.release()
        io.execute { tracker.close() }
        io.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        camera.updateRotation()
        applyPanelWidth()
        glView.requestRender()
    }

    // ---- flow ----------------------------------------------------------------------------------------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionIntro() {
        panel.visibility = View.GONE
        topBar.visibility = View.GONE
        showOverlay(
            getString(R.string.perm_title), getString(R.string.perm_message), spinner = false,
            primary = getString(R.string.perm_grant) to { cameraPermission.launch(Manifest.permission.CAMERA) },
            secondary = getString(R.string.use_photo) to { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    }

    private fun showPermissionNeeded() {
        val blocked = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        showOverlay(
            getString(R.string.perm_denied_title), getString(R.string.perm_denied_message), spinner = false,
            primary = if (blocked)
                getString(R.string.open_settings) to {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            else getString(R.string.perm_grant) to { cameraPermission.launch(Manifest.permission.CAMERA) },
            secondary = getString(R.string.use_photo) to { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    }

    private fun startLive() {
        mode = Mode.LIVE
        showLoading(getString(R.string.loading_camera))
        backToLive.visibility = View.INVISIBLE
        io.execute {
            try {
                tracker.prepareVideo()
            } catch (e: Throwable) {
                Log.e(TAG, "face model failed", e)
                main.post { showError(getString(R.string.error_model, e.message ?: e.javaClass.simpleName), retry = true) }
                return@execute
            }
            main.post {
                renderer.clearFrame()
                camera.start()
                firstFrameTimeout?.let { main.removeCallbacks(it) }
                firstFrameTimeout = Runnable { showError(getString(R.string.error_no_frames), retry = true) }
                main.postDelayed(firstFrameTimeout!!, 12_000L)
            }
        }
    }

    override fun onFirstFrame() {
        firstFrameTimeout?.let { main.removeCallbacks(it) }
        if (mode != Mode.LIVE) return
        hideOverlay()
        setChip(getString(R.string.chip_searching), false)
    }

    override fun onFaceStateChanged(found: Boolean) {
        if (mode != Mode.LIVE) return
        setChip(getString(if (found) R.string.chip_tracking else R.string.chip_searching), found)
    }

    override fun onCameraError(message: String) {
        firstFrameTimeout?.let { main.removeCallbacks(it) }
        showError(message, retry = true)
    }

    private fun enterPhotoMode(uri: Uri) {
        camera.stop()
        firstFrameTimeout?.let { main.removeCallbacks(it) }
        mode = Mode.PHOTO
        showLoading(getString(R.string.loading_photo))
        io.execute {
            try {
                tracker.prepareStill()
                val bmp = decodePhoto(uri)
                val tracked = tracker.detectStill(bmp)
                val pose = tracked?.pose
                val slot = renderer.ring.obtain(bmp.width, bmp.height)
                Canvas(slot.bitmap!!).drawBitmap(bmp, 0f, 0f, null)
                slot.pose = pose
                slot.eyes = tracked?.eyes
                slot.mirror = false
                slot.ambient = CameraController.estimateAmbient(bmp)
                renderer.ring.publish(slot)
                renderer.requestRender()
                bmp.recycle()
                main.post {
                    hideOverlay()
                    backToLive.visibility = View.VISIBLE
                    setChip(getString(if (pose != null) R.string.chip_photo_ok else R.string.chip_photo_no_face), pose != null)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "photo failed", e)
                main.post { showError(getString(R.string.error_photo, e.message ?: e.javaClass.simpleName), retry = false, photo = true) }
            }
        }
    }

    private fun decodePhoto(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_PHOTO_SIDE) {
                    val f = MAX_PHOTO_SIDE.toFloat() / longest
                    decoder.setTargetSize(max(1, (info.size.width * f).roundToInt()), max(1, (info.size.height * f).roundToInt()))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_PHOTO_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Could not read the selected photo")
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return decoded
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    }

    private fun capture() {
        renderer.capture { bmp ->
            if (bmp == null) { toast(getString(R.string.save_failed)); return@capture }
            if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                main.post { pendingSave = bmp; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            } else {
                saveBitmapAsync(bmp)
            }
        }
    }

    private fun saveBitmapAsync(bmp: Bitmap) {
        io.execute {
            try {
                val name = "OptiTry_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OptiTry")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)) throw IllegalStateException("JPEG encode failed")
                } ?: throw IllegalStateException("Could not open output stream")
                if (Build.VERSION.SDK_INT >= 29) {
                    val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, done, null, null)
                }
                toast(getString(R.string.saved_ok))
            } catch (e: Throwable) {
                Log.e(TAG, "save failed", e)
                toast(getString(R.string.save_failed))
            }
        }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ---- selection ------------------------------------------------------------------------------------------

    private fun select(spec: FrameSpec) {
        selected = spec
        selection[spec.category] = spec
        renderer.setSpec(spec)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FRAME, spec.id).apply()
        val pos = adapter.select(spec)
        if (pos >= 0) list.smoothScrollToPosition(pos)
        updateHeader()
    }

    private fun setTab(t: Tab) {
        tab = t
        when (t) {
            Tab.EYEGLASSES, Tab.SUNGLASSES -> {
                category = if (t == Tab.SUNGLASSES) Category.SUNGLASSES else Category.EYEGLASSES
                renderer.lens = null
                renderer.glassesVisible = true
                intensityRow.visibility = View.GONE
                list.adapter = adapter
                refreshPanel()
                select(selection[category] ?: FrameCatalog.byCategory(category).first())
            }
            Tab.LENSES -> {
                renderer.glassesVisible = false
                list.adapter = lensAdapter
                intensityRow.visibility = if (selectedLens != null) View.VISIBLE else View.GONE
                applyLens()
                styleTabs()
                updateHeader()
                list.scrollToPosition(lensAdapter.select(selectedLens))
            }
        }
        styleTabs()
    }

    private fun selectLens(lens: ContactLens?) {
        selectedLens = lens
        if (lens != null) lensOpacity = lens.defaultOpacity
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LENS, lens?.id).apply()
        lensAdapter.select(lens)
        intensityRow.visibility = if (lens != null && tab == Tab.LENSES) View.VISIBLE else View.GONE
        intensitySeek.progress = (lensOpacity * 100f).roundToInt()
        applyLens()
        updateHeader()
    }

    private fun applyLens() {
        renderer.lensOpacity = lensOpacity
        renderer.lens = if (tab == Tab.LENSES) selectedLens else null
    }

    private fun refreshPanel() {
        adapter.submit(FrameCatalog.byCategory(category), selection[category])
        styleTabs()
        updateHeader()
    }

    private fun styleTabs() {
        styleTab(tabEyeglasses, tab == Tab.EYEGLASSES)
        styleTab(tabSunglasses, tab == Tab.SUNGLASSES)
        styleTab(tabLenses, tab == Tab.LENSES)
    }

    private fun updateHeader() {
        if (tab == Tab.LENSES) {
            nameView.text = selectedLens?.name ?: getString(R.string.no_lens)
            subView.text = getString(R.string.lens_sub)
            return
        }
        nameView.text = selected.name
        val material = if (selected.rim == com.optitry.app.eyewear.RimStyle.WIRE) getString(R.string.rim_metal) else getString(R.string.rim_acetate)
        val kind = if (selected.category == Category.EYEGLASSES) getString(R.string.eyeglasses) else getString(R.string.sunglasses)
        subView.text = "$kind · $material"
    }

    // ---- status overlay -------------------------------------------------------------------------------------

    private fun showLoading(text: String) {
        panel.visibility = View.GONE
        showOverlay(text, getString(R.string.loading_hint), spinner = true)
    }

    private fun showError(message: String, retry: Boolean, photo: Boolean = false) {
        panel.visibility = View.GONE
        showOverlay(
            getString(R.string.error_title), message, spinner = false,
            primary = when {
                retry -> getString(R.string.retry) to { startLive() }
                photo -> getString(R.string.choose_another) to { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                else -> null
            },
            secondary = if (retry) getString(R.string.use_photo) to { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } else null
        )
    }

    private fun showOverlay(
        title: String, message: String, spinner: Boolean,
        primary: Pair<String, () -> Unit>? = null, secondary: Pair<String, () -> Unit>? = null
    ) {
        overlay.visibility = View.VISIBLE
        overlaySpinner.visibility = if (spinner) View.VISIBLE else View.GONE
        overlayTitle.text = title
        overlayMessage.text = message
        bindButton(overlayPrimary, primary)
        bindButton(overlaySecondary, secondary)
    }

    private fun bindButton(b: Button, spec: Pair<String, () -> Unit>?) {
        if (spec == null) { b.visibility = View.GONE; return }
        b.visibility = View.VISIBLE
        b.text = spec.first
        b.setOnClickListener { spec.second() }
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        panel.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
    }

    private fun setChip(text: String, good: Boolean) {
        chip.text = text
        (chip.background as? GradientDrawable)?.setColor(if (good) 0xCC0F766E.toInt() else 0x99000000.toInt())
    }

    // ---- view construction ----------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun text(content: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        tab.background = if (active) rounded(0xFF7C3AED.toInt(), 18) else rounded(0x00000000, 18)
        tab.setTextColor(if (active) Color.WHITE else 0xFFB4B4C0.toInt())
    }

    private fun roundButton(iconRes: Int, description: String, onClick: () -> Unit): FrameLayout {
        val size = dp(54)
        return FrameLayout(this).apply {
            background = rounded(0x26FFFFFF, 27, 0x33FFFFFF)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0A0A0F.toInt()) }
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // top bar: title + live status chip
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000))
        }
        topBar.addView(text("OptiTry", 20f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chip = text("", 13f, Color.WHITE).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0x99000000.toInt(), 16)
        }
        topBar.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // bottom panel
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(0xEE141418.toInt())
                val r = 26 * resources.displayMetrics.density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameView = text("", 17f, Color.WHITE, bold = true)
        subView = text("", 12f, 0xFFA0A0B0.toInt())
        titles.addView(nameView)
        titles.addView(subView)
        panel.addView(titles)

        // segmented control: Eyeglasses | Sunglasses | Lenses (contact lenses) - full width so it fits any phone
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0x22FFFFFF, 20)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        fun tabView(label: String, t: Tab) = text(label, 13f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            maxLines = 1
            setOnClickListener { setTab(t) }
        }
        tabEyeglasses = tabView(getString(R.string.eyeglasses), Tab.EYEGLASSES)
        tabSunglasses = tabView(getString(R.string.sunglasses), Tab.SUNGLASSES)
        tabLenses = tabView(getString(R.string.contact_lenses), Tab.LENSES)
        for (t in listOf(tabEyeglasses, tabSunglasses, tabLenses)) tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        adapter = FrameAdapter { select(it) }
        lensAdapter = LensAdapter(ContactLenses.all, getString(R.string.no_lens_short)) { selectLens(it) }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(94)).apply { topMargin = dp(10) })

        // lens strength slider (only while the Lenses tab has a colour selected)
        intensityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        intensityRow.addView(text(getString(R.string.intensity), 12.5f, 0xFFB4B4C0.toInt()))
        intensitySeek = SeekBar(this).apply {
            max = 100
            progress = (lensOpacity * 100f).roundToInt()
            progressTintList = ColorStateList.valueOf(0xFF9F67FF.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.intensity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    lensOpacity = maxOf(value, 15) / 100f
                    intensityValue.text = "${maxOf(value, 15)}%"
                    if (fromUser) renderer.lensOpacity = lensOpacity
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        intensityRow.addView(intensitySeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
        intensityValue = text("${(lensOpacity * 100f).roundToInt()}%", 12.5f, Color.WHITE, bold = true)
        intensityRow.addView(intensityValue)
        panel.addView(intensityRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(roundButton(R.drawable.ic_photo_library, getString(R.string.cd_gallery)) {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        val shutter = FrameLayout(this).apply {
            background = rounded(Color.TRANSPARENT, 40, Color.WHITE).also { (it as GradientDrawable).setStroke(dp(4), Color.WHITE) }
            contentDescription = getString(R.string.cd_capture)
            isClickable = true
            isFocusable = true
            setOnClickListener { capture() }
            addView(View(context).apply { background = rounded(Color.WHITE, 30) },
                FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        }
        controls.addView(shutter, LinearLayout.LayoutParams(dp(78), dp(78)).apply { marginStart = dp(34); marginEnd = dp(34) })
        backToLive = roundButton(R.drawable.ic_photo_camera, getString(R.string.cd_live)) {
            if (hasCameraPermission()) { startLive() } else showPermissionIntro()
        }
        backToLive.visibility = View.INVISIBLE
        controls.addView(backToLive)
        panel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // status overlay (permission / loading / error)
        overlay = FrameLayout(this).apply { setBackgroundColor(0xF20A0A0F.toInt()); isClickable = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        overlaySpinner = ProgressBar(this).apply { isIndeterminate = true }
        column.addView(overlaySpinner, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(20) })
        overlayTitle = text("", 20f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        column.addView(overlayTitle)
        overlayMessage = text("", 14.5f, 0xFFB4B4C0.toInt()).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }
        column.addView(overlayMessage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        overlayPrimary = Button(this).apply { isAllCaps = false; setTextColor(Color.WHITE); background = rounded(0xFF7C3AED.toInt(), 24) }
        overlaySecondary = Button(this).apply { isAllCaps = false; setTextColor(Color.WHITE); background = rounded(0x22FFFFFF, 24) }
        column.addView(overlayPrimary, LinearLayout.LayoutParams(dp(260), dp(48)).apply { topMargin = dp(24) })
        column.addView(overlaySecondary, LinearLayout.LayoutParams(dp(260), dp(48)).apply { topMargin = dp(10) })
        overlay.addView(column, FrameLayout.LayoutParams(dp(420).let { ViewGroup.LayoutParams.WRAP_CONTENT }, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(16) + bars.left, dp(10) + bars.top, dp(16) + bars.right, dp(10))
            panel.setPadding(dp(16) + bars.left, dp(14), dp(16) + bars.right, dp(12) + bars.bottom)
            insets
        }
        applyPanelWidth()
    }

    /** Phones: full width. Tablets / landscape: a centred card no wider than 560dp so it never sprawls. */
    private fun applyPanelWidth() {
        val maxW = dp(560)
        val screenW = resources.displayMetrics.widthPixels
        (panel.layoutParams as FrameLayout.LayoutParams).width = min(screenW, maxW).let { if (screenW <= maxW) ViewGroup.LayoutParams.MATCH_PARENT else it }
        panel.requestLayout()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PREFS = "optitry"
        const val KEY_FRAME = "frame_id"
        const val KEY_LENS = "lens_id"
        const val MAX_PHOTO_SIDE = 1600
    }
}
