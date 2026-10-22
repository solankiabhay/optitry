package com.optitry.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import com.optitry.app.eyewear.FrameSpec
import com.optitry.app.eyewear.Pattern
import kotlin.math.max
import kotlin.math.min

/** Front-view thumbnail of a frame style, drawn from the same lens outline the 3D model is built from. */
class FrameIconView(context: Context) : View(context) {
    var spec: FrameSpec? = null
        set(value) { field = value; invalidate() }

    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val contour = s.shape.contour(56)
        val minX = contour.minOf { it[0] }
        val maxX = contour.maxOf { it[0] }
        val minY = contour.minOf { it[1] }
        val maxY = contour.maxOf { it[1] }
        val cx = s.bridgeWidth / 2f - minX            // lens centre (cm) of the right lens
        val halfSpan = cx + maxX
        val temple = 0.45f
        val totalW = 2f * (halfSpan + temple)
        val totalH = (maxY - minY) + 0.3f
        val pad = resources.displayMetrics.density * 4f
        val scale = min((width - 2 * pad) / totalW, (height - 2 * pad) / totalH)
        val ox = width / 2f
        val midY = (minY + maxY) / 2f
        fun sy(y: Float) = height / 2f - (y - midY) * scale

        val rimPx = max(s.rimWidth * scale, resources.displayMetrics.density * 1.6f)
        rim.strokeWidth = rimPx
        rim.shader = null
        rim.color = s.frameColor
        if (s.pattern == Pattern.TORTOISE || s.pattern == Pattern.GRADIENT) {
            rim.shader = LinearGradient(ox - halfSpan * scale, 0f, ox + halfSpan * scale, height.toFloat(), s.frameColor, s.accentColor, Shader.TileMode.MIRROR)
        } else if (s.pattern == Pattern.CRYSTAL) {
            rim.color = (s.frameColor and 0x00FFFFFF) or (0xB0 shl 24)
        }
        val tintAlpha = (60 + s.tint.opacity * 190).toInt().coerceIn(0, 235)
        fill.color = (s.tint.color and 0x00FFFFFF) or (tintAlpha shl 24)

        for (side in intArrayOf(1, -1)) {
            path.reset()
            contour.forEachIndexed { i, p ->
                val x = ox + side * (cx + p[0]) * scale
                val y = sy(p[1])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, rim)
            // temple stub
            val topOuter = contour.maxByOrNull { it[0] - 0.2f * kotlin.math.abs(it[1] - (maxY - 0.4f)) } ?: contour[0]
            val tx = ox + side * (cx + topOuter[0]) * scale
            val ty = sy(topOuter[1])
            canvas.drawLine(tx, ty, tx + side * temple * scale, ty - 0.1f * scale, rim)
        }
        // bridge
        val by = sy(maxY - (maxY - minY) * 0.30f)
        canvas.drawLine(ox - s.bridgeWidth / 2f * scale, by, ox + s.bridgeWidth / 2f * scale, by, rim)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension(resolveSize((92 * d).toInt(), widthMeasureSpec), resolveSize((46 * d).toInt(), heightMeasureSpec))
    }

}
