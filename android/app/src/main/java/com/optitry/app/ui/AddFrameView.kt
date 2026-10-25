package com.optitry.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.optitry.app.R
import com.optitry.app.eyewear.Category
import com.optitry.app.eyewear.PatternChoice
import com.optitry.app.eyewear.PhotoHints

/** Full-screen form where the shop owner turns a photo of a real frame into a 3D frame. Framework views only. */
class AddFrameView(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {

    interface Callbacks {
        fun onPickPhoto()
        fun onSubmit(request: Request)
        fun onCancel()
    }

    class Request(val name: String, val category: Category, val hints: PhotoHints, val pattern: PatternChoice)

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val preview = ImageView(context)
    private val pickButton = Button(context)
    private val nameField = EditText(context)
    private val sizeField = EditText(context)
    private val spinner = Spinner(context)
    private val message = TextView(context)
    private val progress = ProgressBar(context)
    private val createButton = Button(context)
    private val cancelButton = Button(context)
    private val tabEye = TextView(context)
    private val tabSun = TextView(context)
    private var category = Category.EYEGLASSES
    private var hasPhoto = false

    private val patterns = listOf(
        PatternChoice.AUTO to R.string.pattern_auto, PatternChoice.SOLID to R.string.pattern_solid,
        PatternChoice.TORTOISE to R.string.pattern_tortoise, PatternChoice.CRYSTAL to R.string.pattern_crystal,
        PatternChoice.TWO_TONE to R.string.pattern_two_tone
    )

    init {
        setBackgroundColor(0xF5080810.toInt())
        isClickable = true
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(label(context.getString(R.string.add_frame_title), 20f, Color.WHITE, true))
        col.addView(label(context.getString(R.string.add_frame_tips), 13f, 0xFFB4B4C0.toInt(), false).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })

        preview.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(0xFF15151C.toInt(), 16, 0x33FFFFFF)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        col.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply { topMargin = dp(16) })

        styleButton(pickButton, 0xFF7C3AED.toInt(), context.getString(R.string.choose_photo))
        pickButton.setOnClickListener { callbacks.onPickPhoto() }
        col.addView(pickButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })

        col.addView(label(context.getString(R.string.frame_name), 13f, 0xFFB4B4C0.toInt(), true).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) })
        styleField(nameField, context.getString(R.string.frame_name_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        col.addView(nameField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })

        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(0x22FFFFFF, 20); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        for ((tv, text, cat) in listOf(Triple(tabEye, context.getString(R.string.eyeglasses), Category.EYEGLASSES), Triple(tabSun, context.getString(R.string.sunglasses), Category.SUNGLASSES))) {
            tv.text = text; tv.gravity = Gravity.CENTER
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); tv.typeface = Typeface.DEFAULT_BOLD
            tv.setPadding(dp(8), dp(9), dp(8), dp(9))
            tv.setOnClickListener { category = cat; styleTabs() }
            tabs.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        styleTabs()
        col.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        styleField(sizeField, context.getString(R.string.size_hint), InputType.TYPE_CLASS_TEXT)
        col.addView(sizeField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) })

        col.addView(label(context.getString(R.string.pattern_label), 13f, 0xFFB4B4C0.toInt(), true).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, patterns.map { context.getString(it.second) })
        spinner.background = rounded(0x22FFFFFF, 12)
        col.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })

        message.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); gravity = Gravity.CENTER; visibility = View.GONE }
        col.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        progress.isIndeterminate = true; progress.visibility = View.GONE
        col.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })

        styleButton(createButton, 0xFF7C3AED.toInt(), context.getString(R.string.create_frame))
        createButton.setOnClickListener { submit() }
        styleButton(cancelButton, 0x22FFFFFF, context.getString(R.string.cancel))
        cancelButton.setOnClickListener { callbacks.onCancel() }
        col.addView(createButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(18) })
        col.addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })

        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setPreview(bmp: Bitmap?) {
        preview.setImageBitmap(bmp)
        hasPhoto = bmp != null
        pickButton.text = context.getString(if (hasPhoto) R.string.change_photo else R.string.choose_photo)
        setMessage(null, false)
    }

    fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        createButton.isEnabled = !busy; pickButton.isEnabled = !busy; cancelButton.isEnabled = !busy
        if (busy) setMessage(context.getString(R.string.analysing_frame), false)
    }

    fun setMessage(text: String?, isError: Boolean) {
        message.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        message.text = text ?: ""
        message.setTextColor(if (isError) 0xFFFF8A80.toInt() else 0xFFB4B4C0.toInt())
    }

    /** Clears the form for the next frame. */
    fun reset() {
        setPreview(null); nameField.setText(""); sizeField.setText(""); spinner.setSelection(0)
        category = Category.EYEGLASSES; styleTabs(); setBusy(false); setMessage(null, false)
    }

    private fun submit() {
        val name = nameField.text.toString().trim()
        when {
            !hasPhoto -> setMessage(context.getString(R.string.pick_photo_first), true)
            name.isEmpty() -> setMessage(context.getString(R.string.name_required), true)
            else -> callbacks.onSubmit(Request(name, category, PhotoHints.parse(sizeField.text.toString()), patterns[spinner.selectedItemPosition.coerceIn(0, patterns.size - 1)].first))
        }
    }

    private fun styleTabs() {
        for ((tv, active) in listOf(tabEye to (category == Category.EYEGLASSES), tabSun to (category == Category.SUNGLASSES))) {
            tv.background = if (active) rounded(0xFF7C3AED.toInt(), 18) else rounded(0x00000000, 18)
            tv.setTextColor(if (active) Color.WHITE else 0xFFB4B4C0.toInt())
        }
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun styleField(f: EditText, hint: String, type: Int) {
        f.hint = hint; f.inputType = type; f.setSingleLine(true)
        f.setTextColor(Color.WHITE); f.setHintTextColor(0x88FFFFFF.toInt())
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        f.setPadding(dp(14), 0, dp(14), 0)
        f.background = rounded(0x22FFFFFF, 12)
    }

    private fun styleButton(b: Button, color: Int, text: String) {
        b.text = text; b.isAllCaps = false; b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); b.typeface = Typeface.DEFAULT_BOLD
        b.background = rounded(color, 24)
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * d
        if (stroke != null) setStroke(dp(1), stroke)
    }
}
