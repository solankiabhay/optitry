package com.optitry.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.optitry.app.eyewear.FrameSpec

class FrameAdapter(private val onSelected: (FrameSpec) -> Unit) : RecyclerView.Adapter<FrameAdapter.Holder>() {
    private var items: List<FrameSpec> = emptyList()
    private var selectedId: String? = null

    class Holder(val card: LinearLayout, val icon: FrameIconView, val label: TextView) : RecyclerView.ViewHolder(card)

    fun submit(list: List<FrameSpec>, selected: FrameSpec?) {
        items = list
        selectedId = selected?.id
        notifyDataSetChanged()
    }

    fun select(spec: FrameSpec): Int {
        val old = items.indexOfFirst { it.id == selectedId }
        selectedId = spec.id
        val now = items.indexOfFirst { it.id == spec.id }
        if (old >= 0) notifyItemChanged(old)
        if (now >= 0) notifyItemChanged(now)
        return now
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val d = parent.resources.displayMetrics.density
        val card = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams((104 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * d).toInt()
            }
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            isClickable = true
            isFocusable = true
        }
        val icon = FrameIconView(parent.context)
        card.addView(icon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()))
        val label = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * d).toInt()
        })
        return Holder(card, icon, label)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val spec = items[position]
        val d = h.card.resources.displayMetrics.density
        h.icon.spec = spec
        h.label.text = spec.name
        h.card.contentDescription = spec.name
        val selected = spec.id == selectedId
        h.card.background = GradientDrawable().apply {
            cornerRadius = 14 * d
            setColor(if (selected) 0x337C3AED else 0x1AFFFFFF)
            setStroke((if (selected) 2 else 1).times(d).toInt(), if (selected) 0xFF9F67FF.toInt() else 0x22FFFFFF)
        }
        h.card.setOnClickListener { onSelected(spec) }
    }

    override fun getItemCount() = items.size
}
