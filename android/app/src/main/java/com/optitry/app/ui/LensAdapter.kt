package com.optitry.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.optitry.app.core.ContactLens

/** Round colour chip that looks like an iris: lighter collar, darker outer ring, dark pupil. A null lens draws a dashed "off" circle. */
class IrisSwatchView(context: Context) : View(context) {
    var lens: ContactLens? = null
        set(value) { field = value; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0F.toInt() }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 3f * d
        val l = lens
        if (l == null) {
            ring.shader = null; ring.color = 0xAAFFFFFF.toInt(); ring.strokeWidth = 2f * d
            ring.pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
            c.drawCircle(cx, cy, r, ring)
            ring.strokeWidth = 2.2f * d
            ring.pathEffect = null
            c.drawLine(cx - r * 0.6f, cy + r * 0.6f, cx + r * 0.6f, cy - r * 0.6f, ring)
            return
        }
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(l.color2, l.color, darken(l.color)), floatArrayOf(0.30f, 0.80f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r * 0.30f, pupil)
        ring.shader = null; ring.pathEffect = null; ring.color = 0x55FFFFFF; ring.strokeWidth = 1.2f * d
        c.drawCircle(cx, cy, r, ring)
    }

    private fun darken(c: Int) = Color.rgb((Color.red(c) * 0.45f).toInt(), (Color.green(c) * 0.45f).toInt(), (Color.blue(c) * 0.45f).toInt())

    override fun onMeasure(w: Int, h: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension(resolveSize((52 * d).toInt(), w), resolveSize((52 * d).toInt(), h))
    }
}

/** Contact lens carousel: first entry is "no lens", then every colour in [lenses]. */
class LensAdapter(
    private val lenses: List<ContactLens>,
    private val offLabel: String,
    private val onSelected: (ContactLens?) -> Unit
) : RecyclerView.Adapter<LensAdapter.Holder>() {
    private var selectedId: String? = null

    class Holder(val card: LinearLayout, val swatch: IrisSwatchView, val label: TextView) : RecyclerView.ViewHolder(card)

    fun select(lens: ContactLens?): Int {
        val old = positionOf(selectedId)
        selectedId = lens?.id
        val now = positionOf(selectedId)
        notifyItemChanged(old); notifyItemChanged(now)
        return now
    }

    private fun positionOf(id: String?) = if (id == null) 0 else lenses.indexOfFirst { it.id == id }.let { if (it < 0) 0 else it + 1 }

    override fun getItemCount() = lenses.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val d = parent.resources.displayMetrics.density
        val card = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams((86 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() }
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            isClickable = true; isFocusable = true
        }
        val swatch = IrisSwatchView(parent.context)
        card.addView(swatch, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
        val label = TextView(parent.context).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (5 * d).toInt() })
        return Holder(card, swatch, label)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val lens = if (position == 0) null else lenses[position - 1]
        val d = h.card.resources.displayMetrics.density
        h.swatch.lens = lens
        h.label.text = lens?.name ?: offLabel
        h.card.contentDescription = h.label.text
        val selected = (lens?.id == selectedId)
        h.card.background = GradientDrawable().apply {
            cornerRadius = 14 * d
            setColor(if (selected) 0x337C3AED else 0x1AFFFFFF)
            setStroke((if (selected) 2 else 1).times(d).toInt(), if (selected) 0xFF9F67FF.toInt() else 0x22FFFFFF)
        }
        h.card.setOnClickListener { onSelected(lens) }
    }
}
