package jp.himawari.vumeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Analog-style panel meter for one channel (or stereo sum).
 *
 * Supports two display modes:
 *  - VU mode: rectangular wide dial (aspect ratio ~0.64), warm cream face,
 *    dual scale (top: VU -20/-10/-7/-5/-3/-2/-1/0/+1/+2/+3, bottom: 0/20/40/60/80/100%),
 *    thick red zone arc from 0 to +3, red numbers >= 0, large "VU" legend,
 *    and a bold high-visibility needle.
 *  - Loudness mode: relative scale in LU (-24/-18/-12/-6/-3/0/+3/+6 LU) where
 *    0 LU is aligned with the 0 VU needle position and the red zone runs 0..+6 LU.
 *    Two needles in distinct colours (thin = Short-term 3 s, thick = Integrated)
 *    with "LUFS" and "I" printed on the face.
 */
class MeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class DisplayMode { VU, LOUDNESS }

    var displayMode: DisplayMode = DisplayMode.VU
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var vu: Double = -60.0
    /** Short-term relative loudness in LU (actual short-term LUFS minus target). */
    var shortTermLu: Double = Double.NaN
    /** Integrated relative loudness in LU (actual integrated LUFS minus target). */
    var integratedLu: Double = Double.NaN
    /** Target loudness label shown on the loudness dial (e.g. "0 LU = -24 LKFS"). */
    var loudnessTargetLabel: String = "0 LU = -24 LKFS"
        set(value) {
            field = value
            invalidate()
        }

    var channelLabel: String = "L"
    var needleColor: Int = Color.rgb(22, 22, 24)
        set(value) {
            field = value
            needlePaint.color = value
            channelPaint.color = value
            invalidate()
        }
    var shortTermNeedleColor: Int = Color.rgb(16, 118, 178)
        set(value) {
            field = value
            shortNeedlePaint.color = value
            invalidate()
        }
    var integratedNeedleColor: Int = Color.rgb(198, 92, 14)
        set(value) {
            field = value
            integratedNeedlePaint.color = value
            invalidate()
        }
    var refLabel: String = "0 VU = -18 dBFS"
        set(value) {
            field = value
            invalidate()
        }
    /** Peak (clip) lamp on the dial face, like the LED on a real unit. */
    var peakLamp: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density

    private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val chassisStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(12, 12, 14)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 18, 20)
        style = Paint.Style.FILL
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val faceShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 74, 58, 20)
        style = Paint.Style.STROKE
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val redBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(212, 46, 38)
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        strokeWidth = 2.4f
    }
    private val redTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(212, 46, 38)
        strokeWidth = 2.4f
    }
    private val pctDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        style = Paint.Style.FILL
    }
    private val pctNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val redNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(212, 46, 38)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val redSignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(212, 46, 38)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(98, 90, 74)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 22, 24)
        style = Paint.Style.FILL
    }
    private val shortNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(16, 118, 178)
        style = Paint.Style.FILL
    }
    private val integratedNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(198, 92, 14)
        style = Paint.Style.FILL
    }
    private val needleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(56, 30, 24, 10)
        style = Paint.Style.FILL
    }
    private val channelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 34, 30)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val screwSlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 38, 42)
        strokeWidth = 1.8f
    }
    private val ledOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 30, 34)
        style = Paint.Style.FILL
    }
    private val ledOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 62, 48)
        style = Paint.Style.FILL
    }
    private val ledHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 62, 48)
        style = Paint.Style.FILL
    }
    private val ledLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(160, 70, 66)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(13, 255, 255, 255)
        style = Paint.Style.FILL
    }

    fun setLevel(value: Double) {
        vu = value
        invalidate()
    }

    fun setLoudnessLevels(shortLu: Double, integLu: Double) {
        shortTermLu = shortLu
        integratedLu = integLu
        invalidate()
    }

    companion object {
        private const val DIAL_ASPECT = 0.64f
        private const val HALF_SWEEP_DEG = 46.0
        /** Fraction along the scale arc where 0 VU (100 %) and 0 LU sit. */
        val ZERO_FRACTION: Double = run {
            val v0 = 1.0
            val vMin = 10.0.pow(-40.0 / 20.0)
            val vMax = 10.0.pow(3.0 / 20.0)
            (v0 - vMin) / (vMax - vMin)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specW = MeasureSpec.getSize(widthMeasureSpec)
        var w = specW
        var h = (w * DIAL_ASPECT).toInt()
        when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> h = MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> h = minOf(h, MeasureSpec.getSize(heightMeasureSpec))
        }
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            w = minOf(w, specW)
        }
        setMeasuredDimension(w, h)
    }

    /** Voltage-ratio based scale position: 0.0 at -40 VU (0%), 1.0 at +3 VU. */
    private fun vuFraction(vu: Double): Double {
        val v = 10.0.pow(vu / 20.0)
        val vMin = 10.0.pow(-40.0 / 20.0)
        val vMax = 10.0.pow(3.0 / 20.0)
        return (v - vMin) / (vMax - vMin)
    }

    /** Percentage (0..100%) position matching the voltage-ratio VU scale. */
    private fun percentFraction(pct: Double): Double {
        val v = (pct / 100.0).coerceAtLeast(0.01)
        val vMin = 10.0.pow(-40.0 / 20.0)
        val vMax = 10.0.pow(3.0 / 20.0)
        return (v - vMin) / (vMax - vMin)
    }

    /**
     * Relative loudness (LU) scale position: -24 LU -> 0.0, 0 LU -> ZERO_FRACTION
     * (identical needle angle to 0 VU), +6 LU -> 1.0.
     */
    private fun luFraction(lu: Double): Double {
        val f0 = ZERO_FRACTION
        return if (lu <= 0.0) {
            ((lu + 24.0) / 24.0) * f0
        } else {
            f0 + (lu / 6.0) * (1.0 - f0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10 || h < 10) return

        // Wide rectangular dial box centered in the view.
        var boxW = minOf(w, h / DIAL_ASPECT)
        var boxH = boxW * DIAL_ASPECT
        if (boxH > h) {
            boxH = h
            boxW = boxH / DIAL_ASPECT
        }
        val ox = (w - boxW) / 2f
        val oy = (h - boxH) / 2f
        val ts = (boxW / dp(360f)).coerceIn(0.48f, 1.55f)
        val small = ts < 0.60f

        // --- Outer black case / bezel ---
        val chassis = RectF(ox + dp(1f), oy + dp(1f), ox + boxW - dp(1f), oy + boxH - dp(1f))
        chassisPaint.shader = LinearGradient(
            chassis.left, chassis.top, chassis.left, chassis.bottom,
            Color.rgb(44, 44, 50), Color.rgb(18, 18, 22), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(chassis, dp(8f), dp(8f), chassisPaint)
        canvas.drawRoundRect(chassis, dp(8f), dp(8f), chassisStroke)
        canvas.drawLine(
            chassis.left + dp(8f), chassis.top + dp(1.5f),
            chassis.right - dp(8f), chassis.top + dp(1.5f), highlightPaint
        )

        val bezelInset = boxW * 0.032f
        val bezel = RectF(
            chassis.left + bezelInset, chassis.top + bezelInset,
            chassis.right - bezelInset, chassis.bottom - bezelInset
        )
        canvas.drawRoundRect(bezel, dp(5f), dp(5f), bezelPaint)

        // --- Face (warm cream / ochre backlit plate) ---
        val face = RectF(
            bezel.left + dp(4f), bezel.top + dp(4f),
            bezel.right - dp(4f), bezel.bottom - dp(4f)
        )
        val fw = face.width()
        val fh = face.height()
        val cx = face.left + fw / 2f
        facePaint.shader = RadialGradient(
            cx, face.top + fh * 0.35f, maxOf(fw, fh) * 0.80f,
            intArrayOf(
                Color.rgb(245, 221, 158),
                Color.rgb(236, 206, 134),
                Color.rgb(218, 184, 108)
            ),
            floatArrayOf(0f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(face, dp(3f), dp(3f), facePaint)
        faceShadowPaint.strokeWidth = dp(4f)
        val shadowRect = RectF(
            face.left + dp(2f), face.top + dp(2f),
            face.right - dp(2f), face.bottom - dp(2f)
        )
        canvas.drawRoundRect(shadowRect, dp(3f), dp(3f), faceShadowPaint)

        // Pivot sits slightly below the visible bottom edge of the cream face
        // so the scale arc spans widely across the rectangular dial.
        val pivotY = face.top + fh * 1.08f
        val radius = minOf(fh * 0.80f, fw * 0.56f)

        fun pointAtFraction(f: Double, r: Float): Pair<Float, Float> {
            val theta = Math.toRadians(-HALF_SWEEP_DEG + f * 2.0 * HALF_SWEEP_DEG)
            return Pair(
                cx + (r * sin(theta)).toFloat(),
                pivotY - (r * cos(theta)).toFloat()
            )
        }

        // Clip drawing to the cream face so the needle tail & hub stop cleanly at the bezel
        canvas.save()
        val faceClip = Path().apply { addRoundRect(face, dp(3f), dp(3f), Path.Direction.CW) }
        canvas.clipPath(faceClip)

        if (displayMode == DisplayMode.VU) {
            drawVuScale(canvas, face, cx, pivotY, radius, ts, small, ::pointAtFraction)
        } else {
            drawLoudnessScale(canvas, face, cx, pivotY, radius, ts, small, ::pointAtFraction)
        }

        // Channel label (L+R / L / R) on the lower left of the face
        channelPaint.color = Color.rgb(52, 48, 40)
        channelPaint.textSize = dp(15f * ts)
        canvas.drawText(channelLabel, face.left + dp(24f * ts), face.bottom - dp(12f * ts), channelPaint)

        // Peak LED (top-right of the face)
        val ledX = face.right - dp(15f * ts)
        val ledY = face.top + dp(13f * ts)
        if (peakLamp) {
            canvas.drawCircle(ledX, ledY, dp(9f * ts), ledHaloPaint)
            canvas.drawCircle(ledX, ledY, dp(5f * ts), ledOnPaint)
        } else {
            canvas.drawCircle(ledX, ledY, dp(5f * ts), ledOffPaint)
        }
        if (!small) {
            ledLabelPaint.textSize = dp(6.5f * ts)
            canvas.drawText("PEAK", ledX, ledY + dp(13f * ts), ledLabelPaint)
        }

        // Needles
        if (displayMode == DisplayMode.VU) {
            val f = vuFraction(vu).coerceIn(-0.03, 1.03)
            // Thick needle for high visibility
            val baseW = dp(4.4f * ts.coerceIn(0.75f, 1.25f))
            val tipW = dp(1.9f * ts.coerceIn(0.75f, 1.25f))
            drawNeedleAtFraction(canvas, f, cx, pivotY, radius, baseW, tipW, needleShadowPaint, dp(1.8f), dp(2.6f))
            drawNeedleAtFraction(canvas, f, cx, pivotY, radius, baseW, tipW, needlePaint, 0f, 0f)
        } else {
            val fInteg = if (integratedLu.isNaN()) 0.0 else luFraction(integratedLu).coerceIn(-0.03, 1.03)
            val fShort = if (shortTermLu.isNaN()) 0.0 else luFraction(shortTermLu).coerceIn(-0.03, 1.03)
            // Thick needle = Integrated ("I")
            val iBaseW = dp(5.2f * ts.coerceIn(0.75f, 1.25f))
            val iTipW = dp(2.4f * ts.coerceIn(0.75f, 1.25f))
            drawNeedleAtFraction(canvas, fInteg, cx, pivotY, radius, iBaseW, iTipW, needleShadowPaint, dp(1.8f), dp(2.6f))
            drawNeedleAtFraction(canvas, fInteg, cx, pivotY, radius, iBaseW, iTipW, integratedNeedlePaint, 0f, 0f)
            // Thin needle = Short-term (3 s)
            val sBaseW = dp(2.2f * ts.coerceIn(0.75f, 1.25f))
            val sTipW = dp(0.9f * ts.coerceIn(0.75f, 1.25f))
            drawNeedleAtFraction(canvas, fShort, cx, pivotY, radius * 1.02f, sBaseW, sTipW, needleShadowPaint, dp(1.4f), dp(2.0f))
            drawNeedleAtFraction(canvas, fShort, cx, pivotY, radius * 1.02f, sBaseW, sTipW, shortNeedlePaint, 0f, 0f)
        }

        // Pivot hub at bottom center
        val hubR = dp(13f) * ts.coerceIn(0.65f, 1.3f)
        pivotPaint.shader = RadialGradient(
            cx - dp(3f), pivotY - dp(3f), hubR * 1.2f,
            intArrayOf(Color.rgb(90, 90, 96), Color.rgb(42, 42, 46), Color.rgb(20, 20, 22)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, pivotY, hubR, pivotPaint)
        pivotPaint.shader = null

        // Subtle glass reflection
        val glass = Path()
        glass.moveTo(face.left, face.top)
        glass.lineTo(face.left + fw * 0.58f, face.top)
        glass.lineTo(face.left + fw * 0.28f, face.top + fh * 0.48f)
        glass.lineTo(face.left, face.top + fh * 0.48f)
        glass.close()
        canvas.drawPath(glass, glassPaint)
        canvas.restore()
    }

    private fun drawVuScale(
        canvas: Canvas,
        face: RectF,
        cx: Float,
        pivotY: Float,
        radius: Float,
        ts: Float,
        small: Boolean,
        pointAtFraction: (Double, Float) -> Pair<Float, Float>
    ) {
        val f0 = vuFraction(0.0)
        // Main black arc from -20 VU (0%) up to 0 VU
        val arcPath = Path()
        val steps = 60
        for (i in 0..steps) {
            val f = f0 * i / steps
            val (x, y) = pointAtFraction(f, radius)
            if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
        }
        arcPaint.strokeWidth = dp(2.2f * ts)
        canvas.drawPath(arcPath, arcPaint)

        // Thick red band from 0 VU to +3 VU along the arc
        val bandThickness = dp(11f * ts)
        val rInner = radius
        val rOuter = radius + bandThickness
        val redBandPath = Path()
        val redSteps = 24
        for (i in 0..redSteps) {
            val f = f0 + (1.0 - f0) * i / redSteps
            val (x, y) = pointAtFraction(f, rOuter)
            if (i == 0) redBandPath.moveTo(x, y) else redBandPath.lineTo(x, y)
        }
        for (i in redSteps downTo 0) {
            val f = f0 + (1.0 - f0) * i / redSteps
            val (x, y) = pointAtFraction(f, rInner)
            redBandPath.lineTo(x, y)
        }
        redBandPath.close()
        canvas.drawPath(redBandPath, redBandPaint)

        // Upper scale: VU ticks and numbers (-20/-10/-7/-5/-3/-2/-1/0/+1/+2/+3)
        tickPaint.strokeWidth = dp(2.3f * ts)
        redTickPaint.strokeWidth = dp(2.3f * ts)
        numPaint.textSize = dp(18.5f * ts)
        redNumPaint.textSize = dp(18.5f * ts)

        val majors = listOf(
            -20.0 to "-20", -10.0 to "-10", -7.0 to "-7", -5.0 to "-5",
            -3.0 to "-3", -2.0 to "-2", -1.0 to "-1", 0.0 to "0",
            1.0 to "+1", 2.0 to "+2", 3.0 to "+3"
        )
        val minors = listOf(-40.0, -30.0, -15.0, -9.0, -8.0, -6.0, -4.0, 0.5, 1.5, 2.5)
        for (v in minors) {
            val f = vuFraction(v)
            val red = v >= 0.0
            val rStart = if (red) rOuter else radius
            val rEnd = radius + dp(13f * ts)
            val (x1, y1) = pointAtFraction(f, rStart)
            val (x2, y2) = pointAtFraction(f, rEnd)
            canvas.drawLine(x1, y1, x2, y2, if (red) redTickPaint else tickPaint)
        }
        for ((v, label) in majors) {
            val f = vuFraction(v)
            val red = v >= 0.0
            val rStart = if (red) rOuter else radius
            val rEnd = radius + dp(20f * ts)
            val (x1, y1) = pointAtFraction(f, rStart)
            val (x2, y2) = pointAtFraction(f, rEnd)
            canvas.drawLine(x1, y1, x2, y2, if (red) redTickPaint else tickPaint)
            val (nx, ny) = pointAtFraction(f, radius + dp(34f * ts))
            canvas.drawText(label, nx, ny + dp(6f * ts), if (red) redNumPaint else numPaint)
        }

        // "-" sign on upper-left and "+" sign in red on upper-right
        signPaint.textSize = dp(22f * ts)
        redSignPaint.textSize = dp(22f * ts)
        canvas.drawText("−", face.left + dp(26f * ts), face.top + dp(26f * ts), signPaint)
        canvas.drawText("+", face.right - dp(38f * ts), face.top + dp(26f * ts), redSignPaint)

        // Lower scale: % scale (0 / 20 / 40 / 60 / 80 / 100%)
        pctNumPaint.textSize = dp(10.5f * ts)
        val pctMarks = listOf(0 to "0", 20 to "20", 40 to "40", 60 to "60", 80 to "80", 100 to "100%")
        val pctSubDots = listOf(10, 30, 50, 70, 90)
        val dotR = radius - dp(5.5f * ts)
        for (p in pctSubDots) {
            val f = percentFraction(p.toDouble())
            val (dx, dy) = pointAtFraction(f, dotR)
            canvas.drawCircle(dx, dy, dp(1.3f * ts), pctDotPaint)
        }
        for ((p, label) in pctMarks) {
            val f = percentFraction(p.toDouble())
            val (dx, dy) = pointAtFraction(f, dotR)
            canvas.drawCircle(dx, dy, dp(1.8f * ts), pctDotPaint)
            val (tx, ty) = pointAtFraction(f, radius - dp(16f * ts))
            canvas.drawText(label, tx, ty + dp(4f * ts), pctNumPaint)
        }

        // Large "VU" in lower-center of dial
        titlePaint.textSize = dp(32f * ts)
        canvas.drawText("VU", cx, pivotY - radius * 0.44f, titlePaint)
        if (!small) {
            subPaint.textSize = dp(10.5f * ts)
            canvas.drawText(refLabel, cx, pivotY - radius * 0.44f + dp(16f * ts), subPaint)
        }
    }

    private fun drawLoudnessScale(
        canvas: Canvas,
        face: RectF,
        cx: Float,
        pivotY: Float,
        radius: Float,
        ts: Float,
        small: Boolean,
        pointAtFraction: (Double, Float) -> Pair<Float, Float>
    ) {
        val f0 = ZERO_FRACTION
        // Main arc from -24 LU to 0 LU
        val arcPath = Path()
        val steps = 60
        for (i in 0..steps) {
            val f = f0 * i / steps
            val (x, y) = pointAtFraction(f, radius)
            if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
        }
        arcPaint.strokeWidth = dp(2.2f * ts)
        canvas.drawPath(arcPath, arcPaint)

        // Thick red band from 0 LU to +6 LU along the arc
        val bandThickness = dp(11f * ts)
        val rInner = radius
        val rOuter = radius + bandThickness
        val redBandPath = Path()
        val redSteps = 24
        for (i in 0..redSteps) {
            val f = f0 + (1.0 - f0) * i / redSteps
            val (x, y) = pointAtFraction(f, rOuter)
            if (i == 0) redBandPath.moveTo(x, y) else redBandPath.lineTo(x, y)
        }
        for (i in redSteps downTo 0) {
            val f = f0 + (1.0 - f0) * i / redSteps
            val (x, y) = pointAtFraction(f, rInner)
            redBandPath.lineTo(x, y)
        }
        redBandPath.close()
        canvas.drawPath(redBandPath, redBandPaint)

        // Scale ticks: -24 / -18 / -12 / -6 / -3 / 0 / +3 / +6 LU
        tickPaint.strokeWidth = dp(2.3f * ts)
        redTickPaint.strokeWidth = dp(2.3f * ts)
        numPaint.textSize = dp(16.5f * ts)
        redNumPaint.textSize = dp(16.5f * ts)

        val majors = listOf(
            -24.0 to "-24", -18.0 to "-18", -12.0 to "-12", -6.0 to "-6",
            -3.0 to "-3", 0.0 to "0", 3.0 to "+3", 6.0 to "+6"
        )
        val minors = listOf(-21.0, -15.0, -9.0, -4.5, -1.5, 1.5, 4.5)
        for (lu in minors) {
            val f = luFraction(lu)
            val red = lu >= 0.0
            val rStart = if (red) rOuter else radius
            val rEnd = radius + dp(13f * ts)
            val (x1, y1) = pointAtFraction(f, rStart)
            val (x2, y2) = pointAtFraction(f, rEnd)
            canvas.drawLine(x1, y1, x2, y2, if (red) redTickPaint else tickPaint)
        }
        for ((lu, label) in majors) {
            val f = luFraction(lu)
            val red = lu >= 0.0
            val rStart = if (red) rOuter else radius
            val rEnd = radius + dp(20f * ts)
            val (x1, y1) = pointAtFraction(f, rStart)
            val (x2, y2) = pointAtFraction(f, rEnd)
            canvas.drawLine(x1, y1, x2, y2, if (red) redTickPaint else tickPaint)
            val (nx, ny) = pointAtFraction(f, radius + dp(33f * ts))
            canvas.drawText(label, nx, ny + dp(5.5f * ts), if (red) redNumPaint else numPaint)
        }

        // Lower scale label: "LU" under 0 mark
        pctNumPaint.textSize = dp(11f * ts)
        val (tx0, ty0) = pointAtFraction(f0, radius - dp(15f * ts))
        canvas.drawText("0 LU", tx0, ty0 + dp(4f * ts), pctNumPaint)

        // Central dial markings: "LUFS" and "I" (Integrated needle legend)
        titlePaint.textSize = dp(26f * ts)
        canvas.drawText("LUFS", cx, pivotY - radius * 0.50f, titlePaint)

        // Needle legend showing thick = I (Integrated) and thin = S (Short-term 3s)
        legendPaint.textSize = dp(12.5f * ts)
        legendPaint.color = integratedNeedleColor
        canvas.drawText("━ I (Integrated)", cx - dp(54f * ts), pivotY - radius * 0.34f, legendPaint)
        legendPaint.color = shortTermNeedleColor
        canvas.drawText("─ S (3s)", cx + dp(56f * ts), pivotY - radius * 0.34f, legendPaint)

        if (!small) {
            subPaint.textSize = dp(10.5f * ts)
            canvas.drawText(loudnessTargetLabel, cx, pivotY - radius * 0.22f, subPaint)
        }
    }

    private fun drawNeedleAtFraction(
        canvas: Canvas,
        f: Double,
        cx: Float,
        pivotY: Float,
        radius: Float,
        baseHalfWidth: Float,
        tipHalfWidth: Float,
        paint: Paint,
        offX: Float,
        offY: Float
    ) {
        val theta = Math.toRadians(-HALF_SWEEP_DEG + f * 2.0 * HALF_SWEEP_DEG)
        val sinT = sin(theta).toFloat()
        val cosT = cos(theta).toFloat()
        val tipR = radius * 1.03f
        val tailR = radius * 0.12f
        val tipX = cx + tipR * sinT + offX
        val tipY = pivotY - tipR * cosT + offY
        val tailX = cx - tailR * sinT + offX
        val tailY = pivotY + tailR * cosT + offY
        // Perpendicular unit vector
        val px = cosT
        val py = sinT
        val path = Path()
        path.moveTo(tipX - px * tipHalfWidth, tipY - py * tipHalfWidth)
        path.lineTo(tipX + px * tipHalfWidth, tipY + py * tipHalfWidth)
        path.lineTo(tailX + px * baseHalfWidth, tailY + py * baseHalfWidth)
        path.lineTo(tailX - px * baseHalfWidth, tailY - py * baseHalfWidth)
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
        color = Color.rgb(30, 30, 36)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(62, 62, 72)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
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
        val bg = RectF(0f, 0f, w, barH)
        canvas.drawRoundRect(bg, dp(3f), dp(3f), bgPaint)
        val c = value
        if (!c.isNaN()) {
            val x = center + (center * c).toFloat()
            val paint = when {
                c < -0.3 -> badPaint
                c < 0.3 -> midPaint
                else -> goodPaint
            }
            canvas.drawRect(minOf(center, x), dp(1f), maxOf(center, x), barH - dp(1f), paint)
        }
        canvas.drawRoundRect(bg, dp(3f), dp(3f), borderPaint)
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

/**
 * LED-style digital peak meter (L/R) in dBTP, placed under the VU
 * meters, following IEC 60268-18: scale -60..0 dBFS with the
 * standard's marks, green below the alignment (reference) level,
 * yellow from there, and red from the permitted maximum level
 * (-9 dBFS, EBU R 68 / SMPTE RP 155). The peak hold shows as a
 * single brighter segment.
 */
class LedMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var referenceDbfs: Double = -18.0
        set(v) {
            field = v
            invalidate()
        }

    private var levelL = -60.0
    private var levelR = -60.0
    private var holdL = -60.0
    private var holdR = -60.0

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 220, 225)
        isFakeBoldText = true
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 158)
        textAlign = Paint.Align.CENTER
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(242, 201, 110)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setPeaks(iL: Double, iR: Double, hL: Double, hR: Double) {
        levelL = iL
        levelR = iR
        holdL = hL
        holdR = hR
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    private fun zoneColor(db: Double, lit: Boolean): Int {
        val zone = when {
            db >= -9.0 -> 2
            db >= referenceDbfs -> 1
            else -> 0
        }
        return when {
            lit && zone == 2 -> Color.rgb(245, 65, 55)
            lit && zone == 1 -> Color.rgb(250, 205, 45)
            lit -> Color.rgb(70, 205, 95)
            zone == 2 -> Color.rgb(64, 30, 28)
            zone == 1 -> Color.rgb(64, 54, 22)
            else -> Color.rgb(28, 52, 34)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 40 || h < 20) return
        val minDb = -60.0
        val maxDb = 0.0
        val labelW = dp(16f)
        val scaleH = dp(15f)
        val rowH = (h - scaleH) / 2f
        val segPitch = dp(13f)
        val segW = dp(10f)
        val availW = w - labelW - dp(2f)
        val count = (availW / segPitch).toInt().coerceIn(8, 80)
        val step = (maxDb - minDb) / count

        labelPaint.textSize = dp(12f)
        drawRow(canvas, 0f, rowH, "L", levelL, holdL, labelW, segPitch, segW, count, step, minDb)
        drawRow(canvas, rowH, rowH, "R", levelR, holdR, labelW, segPitch, segW, count, step, minDb)

        // Scale labels (a legible subset of the IEC 60268-18 marks),
        // the reference marker, and the PML mark in red.
        scalePaint.textSize = dp(10.5f)
        refPaint.textSize = dp(10.5f)
        val scaleY = h - dp(1f)
        fun xFor(db: Double): Float =
            labelW + ((db - minDb) / (maxDb - minDb)).toFloat() * count * segPitch - segPitch / 2f
        for (db in listOf(-60.0, -40.0, 0.0)) {
            canvas.drawText(db.toInt().toString(), xFor(db), scaleY, scalePaint)
        }
        if (kotlin.math.abs(referenceDbfs - (-20.0)) > 2.5) {
            canvas.drawText("-20", xFor(-20.0), scaleY, scalePaint)
        }
        canvas.drawText("REF", xFor(referenceDbfs), scaleY, refPaint)
        val pmlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 100, 90)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = dp(10.5f)
        }
        canvas.drawText("-9", xFor(-9.0), scaleY, pmlPaint)
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 150, 158)
            textAlign = Paint.Align.RIGHT
            textSize = dp(10.5f)
        }
        canvas.drawText("dBTP", w - dp(1f), scaleY, unitPaint)
    }

    private fun drawRow(
        canvas: Canvas, top: Float, rowH: Float, label: String,
        level: Double, hold: Double,
        labelW: Float, segPitch: Float, segW: Float,
        count: Int, step: Double, minDb: Double
    ) {
        canvas.drawText(label, 0f, top + rowH * 0.72f, labelPaint)
        val segH = rowH - dp(5f)
        val segTop = top + dp(2.5f)
        val holdSeg = ((hold - minDb) / step).toInt() - 1
        for (i in 0 until count) {
            val thr = minDb + (i + 1) * step
            val lit = level >= thr
            val isHold = i == holdSeg && hold > level
            segPaint.color = zoneColor(thr, lit || isHold)
            segPaint.alpha = if (isHold && !lit) 170 else 255
            val x = labelW + i * segPitch
            canvas.drawRoundRect(
                RectF(x, segTop, x + segW, segTop + segH),
                dp(1.5f), dp(1.5f), segPaint
            )
        }
        segPaint.alpha = 255
    }
}

/**
 * Rotary selector switch like the range knob on real hardware: a metal
 * knob with detents, tick marks and value labels around it. Touch or drag
 * around the knob and it snaps to the nearest position.
 */
class RotarySwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var values: List<Double> = listOf(-24.0, -20.0, -18.0, -16.0, -14.0)
    var value: Double = -18.0
        set(v) {
            field = v
            invalidate()
        }
    /** Fired only when the user turns the knob (not on programmatic set). */
    var onSelected: ((Double) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(72, 72, 82)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 160)
        strokeWidth = 2f
    }
    private val tickSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 184, 75)
        strokeWidth = 2.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(196, 196, 206)
        textAlign = Paint.Align.CENTER
    }
    private val labelSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(242, 201, 110)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val knobEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(14, 14, 16)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(242, 201, 110)
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 22, 26)
        style = Paint.Style.FILL
    }

    private fun index(): Int {
        val i = values.indexOf(value)
        return if (i >= 0) i
        else values.indices.minByOrNull { kotlin.math.abs(values[it] - value) } ?: 0
    }

    private fun angleFor(i: Int): Double =
        if (values.size <= 1) 0.0 else -135.0 + 270.0 * i / (values.size - 1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10 || h < 10) return
        val cx = w / 2f
        val cy = h / 2f
        val knobR = minOf(w, h) / 2f - dp(17f)
        if (knobR <= 0) return
        val sel = index()

        // Detent ticks and value labels around the knob
        labelPaint.textSize = dp(11f)
        labelSelPaint.textSize = dp(11f)
        for (i in values.indices) {
            val a = Math.toRadians(angleFor(i))
            val s = sin(a).toFloat()
            val c = cos(a).toFloat()
            val r1 = knobR + dp(3f)
            val r2 = knobR + dp(7f)
            canvas.drawLine(
                cx + r1 * s, cy - r1 * c, cx + r2 * s, cy - r2 * c,
                if (i == sel) tickSelPaint else tickPaint
            )
            val lr = knobR + dp(14.5f)
            canvas.drawText(
                values[i].toInt().toString(),
                cx + lr * s, cy - lr * c + dp(4f),
                if (i == sel) labelSelPaint else labelPaint
            )
        }
        canvas.drawCircle(cx, cy, knobR + dp(1.5f), ringPaint)

        // Knob body (metal, lit from the upper left)
        knobPaint.shader = RadialGradient(
            cx - knobR * 0.35f, cy - knobR * 0.35f, knobR * 1.5f,
            intArrayOf(
                Color.rgb(98, 98, 110),
                Color.rgb(52, 52, 60),
                Color.rgb(26, 26, 30)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, knobR, knobPaint)
        canvas.drawCircle(cx, cy, knobR, knobEdgePaint)
        knobPaint.shader = null

        // Pointer line
        val a = Math.toRadians(angleFor(sel))
        val s = sin(a).toFloat()
        val c = cos(a).toFloat()
        canvas.drawLine(
            cx + knobR * 0.30f * s, cy - knobR * 0.30f * c,
            cx + knobR * 0.88f * s, cy - knobR * 0.88f * c,
            pointerPaint
        )
        canvas.drawCircle(cx, cy, dp(5f), capPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                val cx = width / 2f
                val cy = height / 2f
                val dx = event.x - cx
                val dy = event.y - cy
                if (dx * dx + dy * dy < dp(6f) * dp(6f)) return true
                val deg = Math.toDegrees(
                    kotlin.math.atan2(dx.toDouble(), -dy.toDouble())
                ).coerceIn(-135.0, 135.0)
                val last = values.size - 1
                if (last > 0) {
                    val idx = Math.round((deg + 135.0) / 270.0 * last).toInt()
                        .coerceIn(0, last)
                    val v = values[idx]
                    if (v != value) {
                        value = v
                        performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONTEXT_CLICK
                        )
                        onSelected?.invoke(v)
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Vertical LED peak meter (L/R columns) in dBTP, standing to the
 * right of the VU meters. Same scale and colour zoning as the
 * horizontal version: IEC 60268-18 range -60..0, green below the
 * reference level, yellow from there, red from -9 dBFS (PML).
 * The peak hold shows as a single brighter segment.
 */
class VerticalLedMeterView(context: Context) : View(context) {

    var referenceDbfs: Double = -18.0

    /** When false, the held-peak marker segment is not drawn. */
    var peakHoldEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var levelL = -60.0
    private var levelR = -60.0
    private var holdL = -60.0
    private var holdR = -60.0

    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 158)
        textAlign = Paint.Align.LEFT
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(242, 201, 110)
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val pmlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 100, 90)
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val chanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 200, 208)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val segPaint = Paint()

    fun setPeaks(l: Double, r: Double, hl: Double, hr: Double) {
        levelL = l
        levelR = r
        holdL = hl
        holdR = hr
        invalidate()
    }

    private fun zoneColor(db: Double, lit: Boolean): Int {
        val zone = when {
            db >= -9.0 -> 2
            db >= referenceDbfs -> 1
            else -> 0
        }
        return when (zone) {
            2 -> if (lit) Color.rgb(240, 70, 58) else Color.argb(48, 240, 70, 58)
            1 -> if (lit) Color.rgb(245, 190, 60) else Color.argb(48, 245, 190, 60)
            else -> if (lit) Color.rgb(74, 200, 110) else Color.argb(48, 74, 200, 110)
        }
    }

    private fun dp(x: Float): Float = x * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < dp(30f) || h < dp(60f)) return
        val minDb = -60.0
        val maxDb = 0.0
        val chanH = dp(14f)
        val labelsW = dp(21f)
        val barsW = w - labelsW
        val gap = dp(4f)
        val barW = (barsW - gap) / 2f
        val plotTop = dp(3f)
        val plotBottom = h - chanH - dp(2f)
        val plotH = plotBottom - plotTop
        val segPitch = dp(8f)
        val segH = dp(5.5f)
        val count = (plotH / segPitch).toInt().coerceAtLeast(8)

        fun drawBar(x0: Float, level: Double, hold: Double) {
            val holdIdx = if (!peakHoldEnabled) -1 else
                (((hold - minDb) / (maxDb - minDb)) * count).toInt()
                    .coerceIn(-1, count - 1)
            for (i in 0 until count) {
                val db = minDb + (i + 0.5) / count * (maxDb - minDb)
                val top = plotBottom - (i + 1) * segPitch + (segPitch - segH) / 2f
                val lit = db <= level || i == holdIdx
                segPaint.color = zoneColor(db, lit)
                canvas.drawRect(x0, top, x0 + barW, top + segH, segPaint)
            }
        }
        drawBar(0f, levelL, holdL)
        drawBar(barW + gap, levelR, holdR)

        chanPaint.textSize = dp(10f)
        canvas.drawText("L", barW / 2f, h - dp(2f), chanPaint)
        canvas.drawText("R", barW + gap + barW / 2f, h - dp(2f), chanPaint)

        // Scale labels: a legible subset of the IEC marks, the PML
        // mark in red, and the reference marker in amber.
        scalePaint.textSize = dp(9f)
        refPaint.textSize = dp(9f)
        pmlPaint.textSize = dp(9f)
        fun yFor(db: Double): Float =
            plotBottom - ((db - minDb) / (maxDb - minDb)).toFloat() * count * segPitch + dp(3f)
        val lx = barsW + dp(3f)
        for (db in listOf(0.0, -40.0, -60.0)) {
            canvas.drawText(db.toInt().toString(), lx, yFor(db), scalePaint)
        }
        if (kotlin.math.abs(referenceDbfs - (-20.0)) > 3.5) {
            canvas.drawText("-20", lx, yFor(-20.0), scalePaint)
        }
        canvas.drawText("-9", lx, yFor(-9.0), pmlPaint)
        canvas.drawText("REF", lx, yFor(referenceDbfs), refPaint)
    }
}

/**
 * Horizontal stereo LED true-peak meter (portrait layout): two
 * segment bars (L over R) with the same zones and peak-hold marker
 * as the vertical meter.
 */
class HorizontalPeakMeterView(context: Context) : View(context) {

    var referenceDbfs: Double = -18.0

    var peakHoldEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var levelL = -60.0
    private var levelR = -60.0
    private var holdL = -60.0
    private var holdR = -60.0

    private val segPaint = Paint()
    private val chanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 200, 208)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setPeaks(l: Double, r: Double, hl: Double, hr: Double) {
        levelL = l
        levelR = r
        holdL = hl
        holdR = hr
        invalidate()
    }

    private fun zoneColor(db: Double, lit: Boolean): Int {
        val zone = when {
            db >= -9.0 -> 2
            db >= referenceDbfs -> 1
            else -> 0
        }
        return when (zone) {
            2 -> if (lit) Color.rgb(240, 70, 58) else Color.argb(48, 240, 70, 58)
            1 -> if (lit) Color.rgb(245, 190, 60) else Color.argb(48, 245, 190, 60)
            else -> if (lit) Color.rgb(74, 200, 110) else Color.argb(48, 74, 200, 110)
        }
    }

    private fun dp(x: Float): Float = x * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            drawBars(canvas)
        } catch (e: Exception) {
            // leave the meter blank this frame
        }
    }

    private fun drawBars(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < dp(80f) || h < dp(28f)) return
        val minDb = -60.0
        val maxDb = 0.0
        val labelW = dp(14f)
        val barsX = labelW
        val barsW = w - barsX
        val gap = dp(3f)
        val barH = (h - gap) / 2f
        val segPitch = dp(8f)
        val segW = dp(5.5f)
        val count = (barsW / segPitch).toInt().coerceAtLeast(8)

        fun drawBar(y0: Float, level: Double, hold: Double) {
            val holdIdx = if (!peakHoldEnabled) -1 else
                (((hold - minDb) / (maxDb - minDb)) * count).toInt()
                    .coerceIn(-1, count - 1)
            for (i in 0 until count) {
                val db = minDb + (i + 0.5) / count * (maxDb - minDb)
                val left = barsX + i * segPitch + (segPitch - segW) / 2f
                val lit = db <= level || i == holdIdx
                segPaint.color = zoneColor(db, lit)
                canvas.drawRect(left, y0, left + segW, y0 + barH, segPaint)
            }
        }
        drawBar(0f, levelL, holdL)
        drawBar(barH + gap, levelR, holdR)

        chanPaint.textSize = dp(10f)
        canvas.drawText("L", labelW / 2f, barH / 2f + dp(3.5f), chanPaint)
        canvas.drawText("R", labelW / 2f, barH + gap + barH / 2f + dp(3.5f), chanPaint)
    }
}


/**
 * Phase panel: a Lissajous (vectorscope) trace — mono signal draws a
 * vertical line, out-of-phase a horizontal one — with a correlation
 * bar and numeric value underneath. Used beside the face in landscape
 * loudness mode and stacked under the peak meter in portrait.
 */
class PhaseScopeView(context: Context) : View(context) {

    private val pairs = FloatArray(512 * 2)
    private var corr = Double.NaN

    fun setData(p: FloatArray, correlation: Double) {
        // Copy into our own buffer so the caller's array can be reused
        System.arraycopy(p, 0, pairs, 0, minOf(p.size, pairs.size))
        corr = correlation
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(x: Float): Float = x * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A drawing fault must never take the meter down mid-show
        try {
            drawScope(canvas)
        } catch (e: Exception) {
            // leave the panel blank this frame
        }
    }

    private fun drawScope(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < dp(60f) || h < dp(60f)) return
        paint.color = Color.rgb(14, 15, 18)
        canvas.drawRect(0f, 0f, w, h, paint)

        textPaint.textSize = dp(10f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.rgb(170, 170, 178)
        canvas.drawText("PHASE", dp(8f), dp(14f), textPaint)
        if (!corr.isNaN()) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                String.format(java.util.Locale.US, "%+.2f", corr),
                w - dp(8f), dp(14f), textPaint
            )
        }

        val barH = dp(24f)
        val scopeTop = dp(18f)
        val scopeH = h - barH - scopeTop
        val side = minOf(w - dp(16f), scopeH).coerceAtLeast(0f)
        val cx = w / 2f
        val cy = scopeTop + scopeH / 2f
        paint.color = Color.argb(50, 200, 200, 210)
        canvas.drawLine(cx - side / 2f, cy, cx + side / 2f, cy, paint)
        canvas.drawLine(cx, cy - side / 2f, cx, cy + side / 2f, paint)

        paint.color = Color.rgb(74, 200, 110)
        val scale = side / 2f / 1.42f
        var i = 0
        while (i + 1 < pairs.size) {
            val l = pairs[i].toDouble()
            val r = pairs[i + 1].toDouble()
            val vx = (l - r) * 0.7071
            val vy = (l + r) * 0.7071
            val px = cx + (vx * scale).toFloat()
            val py = cy - (vy * scale).toFloat()
            canvas.drawRect(px - dp(0.8f), py - dp(0.8f), px + dp(0.8f), py + dp(0.8f), paint)
            i += 2
        }

        val bx0 = dp(8f)
        val bx1 = w - dp(8f)
        val by = h - dp(10f)
        fun xFor(v: Double): Float = (bx0 + ((v + 1.0) / 2.0) * (bx1 - bx0)).toFloat()
        paint.color = Color.argb(60, 200, 200, 210)
        canvas.drawRect(bx0, by - dp(1f), bx1, by + dp(1f), paint)
        paint.color = Color.rgb(120, 120, 128)
        for (v in listOf(-1.0, 0.0, 1.0)) {
            val x = xFor(v)
            canvas.drawRect(x - dp(0.75f), by - dp(4f), x + dp(0.75f), by + dp(4f), paint)
        }
        if (!corr.isNaN()) {
            paint.color = when {
                corr < 0.0 -> Color.rgb(240, 70, 58)
                corr < 0.3 -> Color.rgb(245, 190, 60)
                else -> Color.rgb(74, 200, 110)
            }
            val x = xFor(corr.coerceIn(-1.0, 1.0))
            canvas.drawRect(x - dp(1.5f), by - dp(7f), x + dp(1.5f), by + dp(7f), paint)
        }
    }
}

/**
 * Slide (segmented) switch for the reference level: a pill track
 * with one cell per value; tap or drag across to select. Works
 * horizontally (portrait strip) or vertically (landscape column,
 * highest value at the top).
 */
class SlideSwitchView(context: Context) : View(context) {

    var values: List<Double> = emptyList()
    var vertical: Boolean = false
    var value: Double = -18.0
        set(v) {
            field = v
            invalidate()
        }
    var onSelected: ((Double) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(28, 28, 34)
        style = Paint.Style.FILL
    }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 70, 80)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private fun dp(x: Float): Float = x * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val sel = values.indexOfFirst { it == value }.coerceAtLeast(0)
        val track = RectF(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        val radius = minOf(w, h) / 2f
        canvas.drawRoundRect(track, radius, radius, trackPaint)
        canvas.drawRoundRect(track, radius, radius, trackStroke)
        val inset = dp(3f)
        val thumb = if (!vertical) {
            val cellW = w / n
            RectF(sel * cellW + inset, inset, (sel + 1) * cellW - inset, h - inset)
        } else {
            val cellH = h / n
            val fromTop = n - 1 - sel
            RectF(inset, fromTop * cellH + inset, w - inset, (fromTop + 1) * cellH - inset)
        }
        thumbPaint.shader = LinearGradient(
            thumb.left, thumb.top, thumb.left, thumb.bottom,
            Color.rgb(232, 184, 84), Color.rgb(198, 146, 52),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(thumb, radius - inset, radius - inset, thumbPaint)
        labelPaint.textSize = dp(11f)
        val fm = labelPaint.fontMetrics
        for (i in 0 until n) {
            val cx: Float
            val cy: Float
            if (!vertical) {
                cx = (i + 0.5f) * (w / n)
                cy = h / 2f
            } else {
                cx = w / 2f
                cy = (n - 1 - i + 0.5f) * (h / n)
            }
            labelPaint.color =
                if (i == sel) Color.rgb(26, 22, 10) else Color.rgb(168, 168, 178)
            labelPaint.isFakeBoldText = i == sel
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(values[i].toInt().toString(), cx, baseline, labelPaint)
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val n = values.size
        if (n == 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                val idx = if (!vertical) {
                    (event.x / (width / n)).toInt().coerceIn(0, n - 1)
                } else {
                    val fromTop = (event.y / (height / n)).toInt().coerceIn(0, n - 1)
                    n - 1 - fromTop
                }
                val v = values[idx]
                if (v != value) {
                    value = v
                    performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    )
                    onSelected?.invoke(v)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Lever switch for the reference level, styled like the range
 * lever on analogue gear: a recessed slot with detent ticks and
 * value labels, and a metal lever with grip grooves and an amber
 * index line. Tap or drag the lever across the detents. Works
 * horizontally (portrait strip) or vertically (landscape column,
 * highest value at the top).
 */
class LeverSwitchView(context: Context) : View(context) {

    var values: List<Double> = emptyList()
    var vertical: Boolean = false
    var value: Double = -18.0
        set(v) {
            field = v
            invalidate()
        }
    var onSelected: ((Double) -> Unit)? = null

    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 15, 19)
        style = Paint.Style.FILL
    }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 58, 66)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(130, 130, 140)
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leverPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leverStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 52, 58)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(88, 88, 96)
        strokeWidth = 1.5f
    }
    private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(242, 176, 64)
        strokeWidth = 2f
    }

    private fun dp(x: Float): Float = x * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val sel = values.indexOfFirst { it == value }.coerceAtLeast(0)
        labelPaint.textSize = dp(9.5f)

        if (!vertical) {
            val insetX = dp(13f)
            val trackCy = h - dp(11f)
            fun cx(i: Int): Float =
                insetX + (w - 2 * insetX) * (if (n == 1) 0.5f else i / (n - 1).toFloat())
            // Slot
            val slot = RectF(dp(5f), trackCy - dp(2.5f), w - dp(5f), trackCy + dp(2.5f))
            canvas.drawRoundRect(slot, dp(2.5f), dp(2.5f), slotPaint)
            canvas.drawRoundRect(slot, dp(2.5f), dp(2.5f), slotStroke)
            // Ticks and labels
            labelPaint.textAlign = Paint.Align.CENTER
            for (i in 0 until n) {
                canvas.drawLine(cx(i), trackCy - dp(5f), cx(i), trackCy + dp(5f), tickPaint)
                labelPaint.color =
                    if (i == sel) Color.rgb(242, 201, 110) else Color.rgb(150, 150, 158)
                labelPaint.isFakeBoldText = i == sel
                canvas.drawText(values[i].toInt().toString(), cx(i), dp(10f), labelPaint)
            }
            // Lever
            val lever = RectF(
                cx(sel) - dp(9f), trackCy - dp(10f),
                cx(sel) + dp(9f), trackCy + dp(10f)
            )
            leverPaint.shader = LinearGradient(
                lever.left, lever.top, lever.left, lever.bottom,
                Color.rgb(216, 216, 224), Color.rgb(138, 138, 148),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(lever, dp(3f), dp(3f), leverPaint)
            canvas.drawRoundRect(lever, dp(3f), dp(3f), leverStroke)
            for (g in -1..1) {
                val gx = cx(sel) + g * dp(4f)
                canvas.drawLine(gx, trackCy - dp(6f), gx, trackCy + dp(6f), gripPaint)
            }
            canvas.drawLine(
                lever.left + dp(1.5f), trackCy, lever.right - dp(1.5f), trackCy, indexPaint
            )
        } else {
            val insetY = dp(13f)
            val trackCx = dp(12f)
            fun cy(i: Int): Float {
                val fromTop = n - 1 - i
                return insetY + (h - 2 * insetY) *
                    (if (n == 1) 0.5f else fromTop / (n - 1).toFloat())
            }
            // Slot
            val slot = RectF(trackCx - dp(2.5f), dp(5f), trackCx + dp(2.5f), h - dp(5f))
            canvas.drawRoundRect(slot, dp(2.5f), dp(2.5f), slotPaint)
            canvas.drawRoundRect(slot, dp(2.5f), dp(2.5f), slotStroke)
            // Ticks and labels
            labelPaint.textAlign = Paint.Align.LEFT
            val fm = labelPaint.fontMetrics
            for (i in 0 until n) {
                canvas.drawLine(trackCx - dp(5f), cy(i), trackCx + dp(5f), cy(i), tickPaint)
                labelPaint.color =
                    if (i == sel) Color.rgb(242, 201, 110) else Color.rgb(150, 150, 158)
                labelPaint.isFakeBoldText = i == sel
                canvas.drawText(
                    values[i].toInt().toString(), dp(23f),
                    cy(i) - (fm.ascent + fm.descent) / 2f, labelPaint
                )
            }
            // Lever
            val lever = RectF(
                trackCx - dp(10f), cy(sel) - dp(9f),
                trackCx + dp(10f), cy(sel) + dp(9f)
            )
            leverPaint.shader = LinearGradient(
                lever.left, lever.top, lever.right, lever.top,
                Color.rgb(216, 216, 224), Color.rgb(138, 138, 148),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(lever, dp(3f), dp(3f), leverPaint)
            canvas.drawRoundRect(lever, dp(3f), dp(3f), leverStroke)
            for (g in -1..1) {
                val gy = cy(sel) + g * dp(4f)
                canvas.drawLine(trackCx - dp(6f), gy, trackCx + dp(6f), gy, gripPaint)
            }
        canvas.drawLine(
            trackCx, lever.top + dp(1.5f), trackCx, lever.bottom - dp(1.5f), indexPaint
        )
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val n = values.size
        if (n == 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                val frac = if (!vertical) {
                    val insetX = dp(13f)
                    ((event.x - insetX) / (width - 2 * insetX)).coerceIn(0f, 1f)
                } else {
                    val insetY = dp(13f)
                    1f - ((event.y - insetY) / (height - 2 * insetY)).coerceIn(0f, 1f)
                }
                val idx = Math.round(frac * (n - 1)).coerceIn(0, n - 1)
                val v = values[idx]
                if (v != value) {
                    value = v
                    performHapticFeedback(
                        android.view.HapticFeedbackConstants.CONTEXT_CLICK
                    )
                    onSelected?.invoke(v)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Circular progress ring overlay drawn centered at the press position
 * while the user holds down for 700 ms to open the settings panel.
 * Single uniform colour and constant stroke thickness.
 */
class LongPressRingView(context: Context) : View(context) {

    var ringX: Float = 0f
    var ringY: Float = 0f
    var progress: Float = 0f
    var active: Boolean = false

    private val density = resources.displayMetrics.density
    private fun dp(x: Float) = x * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 240, 224, 180)
        style = Paint.Style.STROKE
        strokeWidth = dp(4.5f)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 200, 100)
        style = Paint.Style.STROKE
        strokeWidth = dp(4.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    fun update(x: Float, y: Float, p: Float) {
        ringX = x
        ringY = y
        progress = p.coerceIn(0f, 1f)
        active = true
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        if (active || visibility == VISIBLE) {
            active = false
            progress = 0f
            visibility = GONE
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || progress <= 0f) return
        val r = dp(34f)
        arcRect.set(ringX - r, ringY - r, ringX + r, ringY + r)
        canvas.drawOval(arcRect, trackPaint)
        canvas.drawArc(arcRect, -90f, 360f * progress, false, ringPaint)
    }
}
