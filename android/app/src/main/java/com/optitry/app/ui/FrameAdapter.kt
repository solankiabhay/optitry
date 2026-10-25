package com.optitry.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.optitry.app.R
import com.optitry.app.eyewear.FrameSpec

/** Frame carousel. Position 0 is always the "+ Add frame" card; the shop's own frames get a star and can be long-pressed to delete. */
class FrameAdapter(
    private val onSelected: (FrameSpec) -> Unit,
    private val onAdd: () -> Unit,
    private val onDelete: (FrameSpec) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<FrameSpec> = emptyList()
    private var selectedId: String? = null
    private var mine: Set<String> = emptySet()

    class Holder(val card: LinearLayout, val icon: FrameIconView, val label: TextView) : RecyclerView.ViewHolder(card)
    class AddHolder(val card: LinearLayout) : RecyclerView.ViewHolder(card)

    fun submit(list: List<FrameSpec>, selected: FrameSpec?, mineIds: Set<String>) {
        items = list
        selectedId = selected?.id
        mine = mineIds
        notifyDataSetChanged()
    }

    /** Marks [spec] selected and returns its adapter position (or -1). */
    fun select(spec: FrameSpec): Int {
        val old = items.indexOfFirst { it.id == selectedId }
        selectedId = spec.id
        val now = items.indexOfFirst { it.id == spec.id }
        if (old >= 0) notifyItemChanged(old + 1)
        if (now >= 0) notifyItemChanged(now + 1)
        return if (now >= 0) now + 1 else -1
    }

    override fun getItemViewType(position: Int) = if (position == 0) TYPE_ADD else TYPE_FRAME
    override fun getItemCount() = items.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val d = parent.resources.displayMetrics.density
        val card = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(((if (viewType == TYPE_ADD) 84 else 104) * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * d).toInt()
            }
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            isClickable = true
            isFocusable = true
        }
        if (viewType == TYPE_ADD) {
            val plus = TextView(parent.context).apply {
                text = "+"; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTextColor(0xFFB794F6.toInt()); typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(plus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()))
            val label = TextView(parent.context).apply {
                text = parent.context.getString(R.string.add_frame)
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            }
            card.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
            return AddHolder(card)
        }
        val icon = FrameIconView(parent.context)
        card.addView(icon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()))
        val label = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * d).toInt()
        })
        return Holder(card, icon, label)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val d = holder.itemView.resources.displayMetrics.density
        if (holder is AddHolder) {
            holder.card.background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(0x0DFFFFFF)
                setStroke((1.5f * d).toInt(), 0x66B794F6, 6f * d, 4f * d)
            }
            holder.card.contentDescription = holder.itemView.context.getString(R.string.add_frame)
            holder.card.setOnClickListener { onAdd() }
            holder.card.setOnLongClickListener(null)
            return
        }
        holder as Holder
        val spec = items[position - 1]
        val isMine = spec.id in mine
        holder.icon.spec = spec
        holder.label.text = if (isMine) holder.itemView.context.getString(R.string.mine_badge, spec.name) else spec.name
        holder.label.setTextColor(if (isMine) 0xFFD4BCFF.toInt() else Color.WHITE)
        holder.card.contentDescription = spec.name
        val selected = spec.id == selectedId
        holder.card.background = GradientDrawable().apply {
            cornerRadius = 14 * d
            setColor(if (selected) 0x337C3AED else 0x1AFFFFFF)
            setStroke((if (selected) 2 else 1).times(d).toInt(), if (selected) 0xFF9F67FF.toInt() else 0x22FFFFFF)
        }
        holder.card.setOnClickListener { onSelected(spec) }
        if (isMine) holder.card.setOnLongClickListener { onDelete(spec); true } else holder.card.setOnLongClickListener(null)
    }

    private companion object {
        const val TYPE_ADD = 0
        const val TYPE_FRAME = 1
    }
}
