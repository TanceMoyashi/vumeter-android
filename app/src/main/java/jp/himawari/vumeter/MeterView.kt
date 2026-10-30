package jp.himawari.vumeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Analog-style single-needle VU dial for one channel.
 * Scale layout follows the classic VU scale: needle deflection is
 * proportional to voltage ratio, 0 VU sits at the 100 % point and the
 * red zone runs from 0 to +3 VU.
 */
class MeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var vu: Double = -60.0
    var channelLabel: String = "L"
    var needleColor: Int = Color.rgb(26, 26, 26)
        set(value) {
            field = value
            needlePaint.color = value
            channelPaint.color = value
            invalidate()
        }
    var refLabel: String = "0 VU = -18 dBFS"
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(247, 239, 219)
        style = Paint.Style.FILL
    }
    private val faceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 55, 45)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 38, 34)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val redArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(192, 39, 45)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 38, 34)
        strokeWidth = 2.5f
    }
    private val redTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(192, 39, 45)
        strokeWidth = 2.5f
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 38, 34)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val redNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(192, 39, 45)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 112, 96)
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 38, 34)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 102, 86)
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 26, 26)
        style = Paint.Style.FILL
    }
    private val channelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(26, 26, 26)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 33, 30)
        style = Paint.Style.FILL
    }

    fun setLevel(value: Double) {
        vu = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var h = (w * 0.88f).toInt()
        when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> h = MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> h = minOf(h, MeasureSpec.getSize(heightMeasureSpec))
        }
        setMeasuredDimension(w, h)
    }

    /** Voltage-ratio based scale position: 0.0 at -40 VU, 1.0 at +3 VU. */
    private fun fraction(vu: Double): Double {
        val v = 10.0.pow(vu / 20.0)
        val vMin = 10.0.pow(-40.0 / 20.0)
        val vMax = 10.0.pow(3.0 / 20.0)
        return (v - vMin) / (vMax - vMin)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10 || h < 10) return

        val face = RectF(dp(6f), dp(6f), w - dp(6f), h - dp(6f))
        canvas.drawRoundRect(face, dp(18f), dp(18f), facePaint)
        canvas.drawRoundRect(face, dp(18f), dp(18f), faceStroke)

        val cx = w / 2f
        val pivotY = h * 0.96f
        val radius = minOf(h * 0.70f, w * 0.55f)
        val halfArc = 46.0 // degrees from vertical

        fun pointAt(vu: Double, r: Float): Pair<Float, Float> {
            val theta = Math.toRadians(-halfArc + fraction(vu) * 2 * halfArc)
            return Pair(
                cx + (r * sin(theta)).toFloat(),
                pivotY - (r * cos(theta)).toFloat()
            )
        }

        // Main arc
        val arcPath = Path()
        val steps = 72
        for (i in 0..steps) {
            val vu = -40.0 + (43.0 * i / steps)
            val (x, y) = pointAt(vu, radius)
            if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
        }
        canvas.drawPath(arcPath, arcPaint)

        // Red zone arc (0 .. +3 VU)
        val redPath = Path()
        for (i in 0..12) {
            val vu = 0.0 + (3.0 * i / 12)
            val (x, y) = pointAt(vu, radius)
            if (i == 0) redPath.moveTo(x, y) else redPath.lineTo(x, y)
        }
        canvas.drawPath(redPath, redArcPaint)

        // Ticks and numbers
        numPaint.textSize = dp(17f)
        redNumPaint.textSize = dp(17f)
        val majors = listOf(
            -20.0 to "-20", -10.0 to "-10", -7.0 to "-7", -5.0 to "-5",
            -3.0 to "-3", -2.0 to "-2", -1.0 to "-1", 0.0 to "0",
            1.0 to "+1", 2.0 to "+2", 3.0 to "+3"
        )
        val minors = listOf(-40.0, -30.0, -15.0, -13.0, -9.0, -8.0, -6.0, -4.0)
        for (vu in minors) {
            val (x1, y1) = pointAt(vu, radius)
            val (x2, y2) = pointAt(vu, radius + dp(7f))
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
        for ((vu, label) in majors) {
            val red = vu >= 0.0
            val (x1, y1) = pointAt(vu, radius)
            val (x2, y2) = pointAt(vu, radius + dp(12f))
            canvas.drawLine(x1, y1, x2, y2, if (red) redTickPaint else tickPaint)
            val (nx, ny) = pointAt(vu, radius + dp(27f))
            canvas.drawText(label, nx, ny + dp(6f), if (red) redNumPaint else numPaint)
        }

        // Percent scale (small, inner): 100 % = 0 VU
        pctPaint.textSize = dp(10f)
        for (pct in listOf(25, 50, 75, 100)) {
            val vu = 20.0 * log10(pct / 100.0)
            val (nx, ny) = pointAt(vu, radius - dp(16f))
            canvas.drawText(pct.toString(), nx, ny + dp(4f), pctPaint)
        }

        // Dial text
        titlePaint.textSize = dp(30f)
        canvas.drawText("VU", cx, pivotY - radius * 0.42f, titlePaint)
        subPaint.textSize = dp(12.5f)
        canvas.drawText("$refLabel  ·  IEC 60268-17", cx, pivotY - radius * 0.42f + dp(22f), subPaint)
        channelPaint.textSize = dp(21f)
        canvas.drawText(channelLabel, cx, pivotY - radius * 0.13f, channelPaint)

        // Needle
        drawNeedle(canvas, vu, cx, pivotY, radius, needlePaint)

        // Pivot
        canvas.drawCircle(cx, pivotY, dp(10f), pivotPaint)
        canvas.drawCircle(cx, pivotY, dp(3.5f), facePaint)
    }

    private fun drawNeedle(
        canvas: Canvas, vu: Double, cx: Float, pivotY: Float,
        radius: Float, paint: Paint
    ) {
        val f = fraction(vu).coerceIn(-0.03, 1.0)
        val theta = Math.toRadians(-46.0 + f * 92.0)
        val tipX = cx + (radius * 0.97f * sin(theta)).toFloat()
        val tipY = pivotY - (radius * 0.97f * cos(theta)).toFloat()
        val tailX = cx - (radius * 0.13f * sin(theta)).toFloat()
        val tailY = pivotY + (radius * 0.13f * cos(theta)).toFloat()
        // Perpendicular for needle base width
        val px = cos(theta).toFloat()
        val py = sin(theta).toFloat()
        val bw = dp(2.6f)
        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(cx + px * bw, pivotY + py * bw)
        path.lineTo(tailX, tailY)
        path.lineTo(cx - px * bw, pivotY - py * bw)
        path.close()
        canvas.drawPath(path, paint)
    }
}

/**
 * Phase correlation meter: -1 (out of phase) on the left, +1 (in phase /
 * mono-compatible) on the right, filled from the center.
 */
class CorrelationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var value: Double = Double.NaN

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 38, 44)
        style = Paint.Style.FILL
    }
    private val goodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(64, 192, 96)
        style = Paint.Style.FILL
    }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 194, 41)
        style = Paint.Style.FILL
    }
    private val badPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 49, 49)
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 158)
        textAlign = Paint.Align.CENTER
    }

    fun setCorrelation(c: Double) {
        value = c
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(30f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val barH = dp(12f)
        val center = w / 2f
        canvas.drawRect(0f, 0f, w, barH, bgPaint)
        val c = value
        if (!c.isNaN()) {
            val x = center + (center * c).toFloat()
            val paint = when {
                c < -0.3 -> badPaint
                c < 0.3 -> midPaint
                else -> goodPaint
            }
            canvas.drawRect(minOf(center, x), 0f, maxOf(center, x), barH, paint)
        }
        tickPaint.textSize = dp(9f)
        val labels = listOf(-1.0 to "-1", -0.5 to "", 0.0 to "0", 0.5 to "", 1.0 to "+1")
        for ((pos, label) in labels) {
            val x = center + (center * pos).toFloat()
            if (label.isNotEmpty()) {
                canvas.drawText(label, x, barH + dp(11f), tickPaint)
            }
        }
    }
}

/** Two horizontal peak bars in dBFS (-60 .. 0) with a peak-hold marker. */
class PeakBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var instL = -60.0
    var instR = -60.0
    var holdL = -60.0
    var holdR = -60.0

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 38, 44)
        style = Paint.Style.FILL
    }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(64, 192, 96)
        style = Paint.Style.FILL
    }
    private val yellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 194, 41)
        style = Paint.Style.FILL
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 49, 49)
        style = Paint.Style.FILL
    }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 245, 245)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 220, 225)
        textSize = dp(12f)
        isFakeBoldText = true
    }

    fun setPeaks(iL: Double, iR: Double, hL: Double, hR: Double) {
        instL = iL; instR = iR; holdL = hL; holdR = hR
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(52f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val labelW = dp(18f)
        val barW = w - labelW - dp(4f)
        drawBar(canvas, 0f, "L", instL, holdL, labelW, barW)
        drawBar(canvas, dp(28f), "R", instR, holdR, labelW, barW)
    }

    private fun drawBar(
        canvas: Canvas, top: Float, label: String,
        inst: Double, hold: Double, labelW: Float, barW: Float
    ) {
        val barH = dp(18f)
        canvas.drawText(label, 0f, top + dp(14f), labelPaint)
        val left = labelW
        canvas.drawRect(left, top, left + barW, top + barH, bgPaint)
        val frac = ((inst + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        if (frac > 0f) {
            // Zone-colored segments
            val greenEnd = (51.0 / 60.0).toFloat() // up to -9 dBFS
            val yellowEnd = (57.0 / 60.0).toFloat() // up to -3 dBFS
            val g = minOf(frac, greenEnd)
            canvas.drawRect(left, top, left + barW * g, top + barH, greenPaint)
            if (frac > greenEnd) {
                val yEnd = minOf(frac, yellowEnd)
                canvas.drawRect(left + barW * greenEnd, top, left + barW * yEnd, top + barH, yellowPaint)
            }
            if (frac > yellowEnd) {
                canvas.drawRect(left + barW * yellowEnd, top, left + barW * frac, top + barH, redPaint)
            }
        }
        val holdFrac = ((hold + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        if (holdFrac > 0f) {
            val x = left + barW * holdFrac
            canvas.drawRect(x - dp(1f), top, x + dp(1f), top + barH, holdPaint)
        }
    }
}
