package app.turntablerpm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** Scrolling speed graph: last 30 s, one point per 0.1 s, centred on the target speed. */
class GraphView(context: Context) : View(context) {

    private val pts = FloatArray(300)
    private var count = 0
    private var head = 0

    var target = 0.0

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BCD4")
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#777777")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 12f * density
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#0D0D0D") }
    private val path = Path()

    fun add(v: Float) {
        pts[head] = v
        head = (head + 1) % pts.size
        if (count < pts.size) count++
        invalidate()
    }

    fun clear() {
        count = 0
        head = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (count < 2) {
            canvas.drawText("Speed graph appears while the platter spins", 8f * density, h / 2, textPaint)
            return
        }

        val start = (head - count + pts.size) % pts.size
        var sum = 0.0
        var maxDev = 0.0
        val center = if (target > 0) target else {
            for (i in 0 until count) sum += pts[(start + i) % pts.size]
            sum / count
        }
        for (i in 0 until count) {
            maxDev = max(maxDev, abs(pts[(start + i) % pts.size] - center))
        }
        // At least ±1 % visible; grow to fit whatever is on screen (capped at ±50 %).
        val halfRange = minOf(max(center * 0.01, maxDev * 1.15), center * 0.5).coerceAtLeast(0.1)
        fun yOf(v: Double): Float = (h / 2 - (v - center) / halfRange * (h / 2 * 0.9)).toFloat()

        // Grid: target line and ±half-range lines.
        canvas.drawLine(0f, yOf(center), w, yOf(center), targetPaint)
        canvas.drawLine(0f, yOf(center + halfRange * 0.5), w, yOf(center + halfRange * 0.5), gridPaint)
        canvas.drawLine(0f, yOf(center - halfRange * 0.5), w, yOf(center - halfRange * 0.5), gridPaint)
        val pct = halfRange * 0.5 / center * 100.0
        canvas.drawText(String.format("+%.2f %%", pct), 4f * density, yOf(center + halfRange * 0.5) - 4f * density, textPaint)
        canvas.drawText(String.format("−%.2f %%", pct), 4f * density, yOf(center - halfRange * 0.5) + 14f * density, textPaint)
        if (target > 0) {
            canvas.drawText(RpmAnalyzer.presetLabel(target), w - 40f * density, yOf(center) - 4f * density, textPaint)
        }

        path.reset()
        val step = w / (pts.size - 1)
        val xOffset = (pts.size - count) * step
        for (i in 0 until count) {
            val v = pts[(start + i) % pts.size].toDouble()
            val x = xOffset + i * step
            val y = yOf(v).coerceIn(0f, h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
