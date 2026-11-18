package jp.himawari.vumeter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private data class InputOption(val label: String, val device: AudioDeviceInfo?)

    data class LoudnessPreset(val name: String, val targetLufs: Double, val unit: String) {
        val displayLabel: String
            get() = "$name（${targetLufs.toInt()} $unit）"
        val dialTargetLabel: String
            get() = "0 LU = ${targetLufs.toInt()} $unit（$name）"
    }

    companion object {
        private const val LONG_PRESS_MS = 700L
        private const val SLIDE_CANCEL_DP = 24f

        val LOUDNESS_PRESETS = listOf(
            LoudnessPreset("ARIB TR-B32（日本・放送）", -24.0, "LKFS"),
            LoudnessPreset("EBU R 128（欧州・放送）", -23.0, "LUFS"),
            LoudnessPreset("ATSC A/85（米国・放送）", -24.0, "LKFS"),
            LoudnessPreset("ライブ・PA（目安）", -18.0, "LUFS"),
            LoudnessPreset("YouTube", -14.0, "LUFS"),
            LoudnessPreset("Spotify", -14.0, "LUFS"),
            LoudnessPreset("Amazon Music", -14.0, "LUFS"),
            LoudnessPreset("Apple Music", -16.0, "LUFS"),
            LoudnessPreset("Netflix", -27.0, "LUFS")
        )
    }

    private lateinit var audioManager: AudioManager
    private lateinit var meterAvg: MeterView
    private lateinit var meterRow: LinearLayout
    private lateinit var meterL: MeterView
    private lateinit var meterR: MeterView
    private lateinit var ledMeter: VerticalLedMeterView
    private lateinit var statusText: TextView
    private lateinit var bigPeakText: TextView
    private lateinit var infoText: TextView
    private lateinit var settingsStateText: TextView
    private lateinit var startButton: Button
    private lateinit var holdButton: Button
    private lateinit var runLamp: View
    private lateinit var leverSwitch: LeverSwitchView
    private lateinit var stageView: LinearLayout
    private lateinit var stageTop: LinearLayout
    private lateinit var meterAreaView: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var rootMain: LinearLayout
    private lateinit var longPressRing: LongPressRingView
    private lateinit var settingsOverlay: ScrollView
    private var settingsShown = false

    private lateinit var modeVuButton: Button
    private lateinit var modeLoudnessButton: Button
    private val presetButtons = mutableListOf<Button>()
    private var selectedPresetIndex = 0
    private var displayMode: MeterView.DisplayMode = MeterView.DisplayMode.VU

    private lateinit var toneButton: Button
    private val toneModeButtons = mutableMapOf<TonePlayer.Mode, Button>()
    private lateinit var deviceButton: Button
    private lateinit var engineButton: Button
    private lateinit var resetButton: Button
    private lateinit var refreshButton: Button
    private var selectedDeviceIndex = 0

    private var options: List<InputOption> = emptyList()
    private var engine: VuEngine? = null
    private var recorder: AudioRecord? = null
    private var aaudio: AAudioCapture? = null
    private var useAAudio = false
    private var captureThread: Thread? = null
    @Volatile private var running = false
    private var sampleRate = 48000
    private var channels = 2
    private var referenceDbfs = -18.0

    private lateinit var logButton: Button
    private val tonePlayer = TonePlayer()
    private var csvLogger: CsvLogger? = null
    private var logging = false
    private var captureStartMs = 0L

    /** Long-press state for opening the settings panel (700 ms hold). */
    private var pressActive = false
    private var pressDownX = 0f
    private var pressDownY = 0f
    private var pressStartMs = 0L
    private val longPressTick = object : Runnable {
        override fun run() {
            if (!pressActive || settingsShown) return
            val elapsed = android.os.SystemClock.elapsedRealtime() - pressStartMs
            val progress = (elapsed.toFloat() / LONG_PRESS_MS.toFloat()).coerceIn(0f, 1f)
            longPressRing.update(pressDownX, pressDownY, progress)
            if (elapsed >= LONG_PRESS_MS) {
                pressActive = false
                cancelPendingTap()
                longPressRing.hide()
                showSettings()
            } else {
                handler.postDelayed(this, 16)
            }
        }
    }

    /** Tap / double-tap state for the front-panel gestures (v1.21):
     *  single tap toggles VU <-> loudness, double tap toggles start/stop. */
    private var lastTapUpMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var doubleTapPress = false
    private val singleTapAction = Runnable {
        displayMode = if (displayMode == MeterView.DisplayMode.VU) {
            MeterView.DisplayMode.LOUDNESS
        } else {
            MeterView.DisplayMode.VU
        }
        syncDisplayMode()
    }

    private fun cancelPendingTap() {
        handler.removeCallbacks(singleTapAction)
        lastTapUpMs = 0L
        doubleTapPress = false
    }

    /** LED-meter display levels with the IEC 60268-18 style return
     *  (20 dB fall in 1.7 s, about 11.8 dB/s). */
    private val dispTp = doubleArrayOf(-60.0, -60.0)
    private var lastTickMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private val uiLoop = object : Runnable {
        override fun run() {
            val e = engine
            if (running && e != null) {
                meterAvg.setLevel(e.vuAverage())
                meterL.setLevel(e.vu(0))
                meterR.setLevel(e.vu(1))

                val target = currentPreset().targetLufs
                val sLufs = e.shortTermLufs()
                val iLufs = e.integratedLufs()
                val sLu = if (sLufs.isNaN()) Double.NaN else sLufs - target
                val iLu = if (iLufs.isNaN()) Double.NaN else iLufs - target
                meterAvg.setLoudnessLevels(sLu, iLu)
                val sLuL = e.shortTermLufsCh(0)
                val iLuL = e.integratedLufsCh(0)
                val sLuR = e.shortTermLufsCh(1)
                val iLuR = e.integratedLufsCh(1)
                meterL.setLoudnessLevels(
                    if (sLuL.isNaN()) Double.NaN else sLuL - target,
                    if (iLuL.isNaN()) Double.NaN else iLuL - target
                )
                meterR.setLoudnessLevels(
                    if (sLuR.isNaN()) Double.NaN else sLuR - target,
                    if (iLuR.isNaN()) Double.NaN else iLuR - target
                )

                meterAvg.peakLamp = e.clip[0] || e.clip[1]
                meterL.peakLamp = e.clip[0]
                meterR.peakLamp = e.clip[1]
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = if (lastTickMs == 0L) 0.033
                    else ((now - lastTickMs) / 1000.0).coerceIn(0.005, 0.25)
                lastTickMs = now
                for (ch in 0..1) {
                    val inst = e.tpInstDbfs[ch]
                    val decayed = dispTp[ch] - 11.76 * dt
                    dispTp[ch] = if (inst > decayed) inst else decayed
                }
                ledMeter.setPeaks(
                    dispTp[0], dispTp[1],
                    e.tpPeakDbfs(0), e.tpPeakDbfs(1)
                )
                tickCount++
                if (tickCount % 5 == 0) {
                    bigPeakText.text = String.format(
                        java.util.Locale.US,
                        "L %6.1f dBTP    R %6.1f dBTP",
                        e.tpPeakDbfs(0), e.tpPeakDbfs(1)
                    )
                    val c = e.correlation()
                    infoText.text = String.format(
                        java.util.Locale.US,
                        "VU   L %6.1f   R %6.1f\nLOUDNESS  M %s  S %s  I %s LUFS    相関 %s",
                        e.vu(0), e.vu(1),
                        fmtLufs(e.momentaryLufs()), fmtLufs(sLufs),
                        fmtLufs(iLufs),
                        if (c.isNaN()) " ---" else String.format(java.util.Locale.US, "%+.2f", c)
                    )
                }
                if (logging && tickCount % 6 == 0) {
                    writeLogRow(e)
                }
                handler.postDelayed(this, 33)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AudioManager::class.java)
        buildUi()
        // Predictive back (API 33+): with the settings panel open, the back
        // button / gesture returns to the meter panel instead of finishing.
        // (Older APIs use the onBackPressed override below.)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY
            ) {
                if (settingsShown) hideSettings() else finish()
            }
        }
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        startCapture()
    }

    override fun onDestroy() {
        cancelLongPress()
        stopCapture()
        tonePlayer.stop()
        csvLogger?.stop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()
    private fun dpF(x: Float): Float = x * resources.displayMetrics.density

    private fun currentPreset(): LoudnessPreset =
        LOUDNESS_PRESETS[selectedPresetIndex.coerceIn(0, LOUDNESS_PRESETS.lastIndex)]

    private fun buildUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(13, 13, 16))
        }

        // Main screen: the meters plus always-visible readouts (v1.18).
        // No app title, no instructions, and no settings controls on the main screen.
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 13, 16))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        mainLayout.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(
                dp(10) + bars.left, dp(8) + bars.top,
                dp(10) + bars.right, dp(8) + bars.bottom
            )
            insets
        }
        rootMain = mainLayout

        stageView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(22, 22, 27))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(48, 48, 56))
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        stageTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        meterAreaView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        meterAvg = MeterView(this).apply {
            channelLabel = "L+R"
        }
        meterAreaView.addView(
            meterAvg,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        meterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        meterL = MeterView(this).apply {
            channelLabel = "L"
        }
        meterR = MeterView(this).apply {
            channelLabel = "R"
            needleColor = Color.rgb(22, 22, 24)
        }
        meterRow.addView(
            meterL,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        meterRow.addView(
            meterR,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        meterAreaView.addView(
            meterRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        stageTop.addView(
            meterAreaView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        // Slim vertical LED true-peak meter (dBTP) to the right of the meters,
        // with a small HOLD toggle for the peak-hold marker underneath it
        ledMeter = VerticalLedMeterView(this)
        ledMeter.referenceDbfs = referenceDbfs
        val ledColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val ledColLp = LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT)
        ledColLp.setMargins(dp(5), 0, 0, 0)
        ledColumn.addView(
            ledMeter,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        holdButton = hwButton("HOLD", compact = true)
        holdButton.setOnClickListener {
            ledMeter.peakHoldEnabled = !ledMeter.peakHoldEnabled
            syncHoldButton()
        }
        val holdLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(28)
        ).apply { setMargins(0, dp(4), 0, 0) }
        ledColumn.addView(holdButton, holdLp)
        stageTop.addView(ledColumn, ledColLp)

        stageView.addView(
            stageTop,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        mainLayout.addView(
            stageView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Front-panel readouts (always visible, v1.18): peak dBTP, VU/loudness
        // values, current settings values (small), and the measurement status line.
        bigPeakText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(238, 238, 244))
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
            text = "L   --.- dBTP    R   --.- dBTP"
        }
        mainLayout.addView(bigPeakText)

        infoText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.rgb(205, 205, 214))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
            text = "VU   L    ---   R    ---\nLOUDNESS  M  --.-  S  --.-  I  --.- LUFS    相関  ---"
        }
        mainLayout.addView(infoText)

        settingsStateText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(160, 160, 168))
            setPadding(0, dp(4), 0, 0)
            text = ""
        }
        mainLayout.addView(settingsStateText)

        statusText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(160, 160, 168))
            setPadding(0, dp(2), 0, 0)
            text = ""
        }
        // Run-state lamp (green = measuring, red = stopped) + status line.
        // The front has no buttons (v1.21): tap = mode, double-tap = start/stop,
        // long-press = settings; the buttons live in the settings panel.
        runLamp = View(this).apply {
            background = lampDrawable(false)
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lampLp = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
            setMargins(dp(1), dp(2), dp(6), 0)
        }
        statusRow.addView(runLamp, lampLp)
        statusRow.addView(
            statusText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        mainLayout.addView(statusRow)

        // Attach the front gesture handler: tap / double-tap / 700 ms long-press
        val longPressTouchListener = View.OnTouchListener { _, event ->
            handleMainTouch(event)
            true
        }
        // Peak meter (LED) touch: long-press opens settings as elsewhere,
        // and a double-tap resets the peaks / integrated loudness.
        var lastLedDownMs = 0L
        var lastLedDownX = 0f
        var lastLedDownY = 0f
        var ledTapSwallowed = false
        val ledTouchListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dx = event.rawX - lastLedDownX
                    val dy = event.rawY - lastLedDownY
                    if (lastLedDownMs > 0L && now - lastLedDownMs < 350 &&
                        dx * dx + dy * dy < dpF(48f) * dpF(48f)
                    ) {
                        cancelLongPress()
                        resetPeaks()
                        ledTapSwallowed = true
                        lastLedDownMs = 0L
                        return@OnTouchListener true
                    }
                    lastLedDownMs = now
                    lastLedDownX = event.rawX
                    lastLedDownY = event.rawY
                    ledTapSwallowed = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ledTapSwallowed) {
                        ledTapSwallowed = false
                        return@OnTouchListener true
                    }
                }
                else -> {
                    if (ledTapSwallowed) return@OnTouchListener true
                }
            }
            // The LED meter keeps its own gestures (double-tap = reset);
            // face tap gestures are disabled here, long-press still works.
            handleMainTouch(event, allowTapGestures = false)
            true
        }
        mainLayout.setOnTouchListener(longPressTouchListener)
        stageView.setOnTouchListener(longPressTouchListener)
        meterAvg.setOnTouchListener(longPressTouchListener)
        meterL.setOnTouchListener(longPressTouchListener)
        meterR.setOnTouchListener(longPressTouchListener)
        ledMeter.setOnTouchListener(ledTouchListener)
        bigPeakText.setOnTouchListener(longPressTouchListener)
        infoText.setOnTouchListener(longPressTouchListener)
        settingsStateText.setOnTouchListener(longPressTouchListener)
        statusText.setOnTouchListener(longPressTouchListener)

        rootFrame.addView(
            mainLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Circular progress ring overlay for 700ms long-press
        longPressRing = LongPressRingView(this).apply {
            visibility = View.GONE
        }
        rootFrame.addView(
            longPressRing,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Settings panel overlay (opened only via 700ms long-press)
        buildSettingsPanel()
        rootFrame.addView(
            settingsOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setRefLabels()
        syncDisplayMode()
        syncHoldButton()
        syncRunLamp()
        updateToneButton()
        syncEngineButtons()
        syncDeviceButtons()
        applyOrientation(resources.configuration.orientation)
        setContentView(rootFrame)
        rootFrame.requestApplyInsets()
    }

    private fun handleMainTouch(event: MotionEvent, allowTapGestures: Boolean = true) {
        if (settingsShown) return
        val rawX = event.rawX
        val rawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (allowTapGestures) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dx = rawX - lastTapX
                    val dy = rawY - lastTapY
                    doubleTapPress = lastTapUpMs > 0L && now - lastTapUpMs < 330 &&
                        dx * dx + dy * dy < dpF(48f) * dpF(48f)
                    if (doubleTapPress) handler.removeCallbacks(singleTapAction)
                }
                pressActive = true
                pressDownX = rawX
                pressDownY = rawY
                pressStartMs = android.os.SystemClock.elapsedRealtime()
                longPressRing.update(pressDownX, pressDownY, 0.01f)
                handler.removeCallbacks(longPressTick)
                handler.postDelayed(longPressTick, 16)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressActive) {
                    val dx = rawX - pressDownX
                    val dy = rawY - pressDownY
                    val limit = dpF(SLIDE_CANCEL_DP)
                    if (dx * dx + dy * dy > limit * limit) {
                        cancelLongPress()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasPressActive = pressActive
                cancelLongPress()
                if (allowTapGestures && wasPressActive) {
                    if (doubleTapPress) {
                        doubleTapPress = false
                        lastTapUpMs = 0L
                        if (running) stopCapture() else startCapture()
                    } else {
                        lastTapUpMs = android.os.SystemClock.elapsedRealtime()
                        lastTapX = rawX
                        lastTapY = rawY
                        handler.removeCallbacks(singleTapAction)
                        handler.postDelayed(singleTapAction, 330)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelLongPress()
            }
        }
    }

    private fun cancelLongPress() {
        pressActive = false
        handler.removeCallbacks(longPressTick)
        if (::longPressRing.isInitialized) {
            longPressRing.hide()
        }
    }

    private fun syncHoldButton() {
        if (!::holdButton.isInitialized || !::ledMeter.isInitialized) return
        holdButton.setTextColor(
            if (ledMeter.peakHoldEnabled) Color.rgb(242, 201, 110)
            else Color.rgb(150, 150, 158)
        )
    }

    private fun lampDrawable(on: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (on) Color.rgb(74, 200, 110) else Color.rgb(240, 70, 58))
        }

    private fun syncRunLamp() {
        if (::runLamp.isInitialized) runLamp.background = lampDrawable(running)
    }

    private fun setStartButtons(label: String) {
        if (::startButton.isInitialized) startButton.text = label
        syncRunLamp()
    }

    private fun updateSettingsStateText() {
        if (!::settingsStateText.isInitialized) return
        val modeLabel = if (displayMode == MeterView.DisplayMode.VU) "VU" else "ラウドネス"
        settingsStateText.text =
            "基準 0 VU = ${referenceDbfs.toInt()} dBFS　モード $modeLabel"
    }

    private fun setRefLabels() {
        val label = "0 VU = ${referenceDbfs.toInt()} dBFS"
        meterAvg.refLabel = label
        meterL.refLabel = label
        meterR.refLabel = label
        if (::ledMeter.isInitialized) ledMeter.referenceDbfs = referenceDbfs
        if (::leverSwitch.isInitialized) leverSwitch.value = referenceDbfs
        updateSettingsStateText()
    }

    private fun syncDisplayMode() {
        val targetLabel = currentPreset().dialTargetLabel
        for (m in listOf(meterAvg, meterL, meterR)) {
            m.displayMode = displayMode
            m.loudnessTargetLabel = targetLabel
        }
        if (::modeVuButton.isInitialized && ::modeLoudnessButton.isInitialized) {
            val isVu = displayMode == MeterView.DisplayMode.VU
            modeVuButton.setTextColor(
                if (isVu) Color.rgb(242, 201, 110) else Color.rgb(220, 220, 228)
            )
            modeVuButton.text = (if (isVu) "● " else "") + "VU メーター"
            modeLoudnessButton.setTextColor(
                if (!isVu) Color.rgb(242, 201, 110) else Color.rgb(220, 220, 228)
            )
            modeLoudnessButton.text = (if (!isVu) "● " else "") + "ラウドネス (LUFS / I)"
        }
        for (i in presetButtons.indices) {
            val active = i == selectedPresetIndex
            val b = presetButtons[i]
            b.setTextColor(
                if (active) Color.rgb(242, 201, 110) else Color.rgb(225, 225, 232)
            )
            b.text = (if (active) "● " else "   ") + LOUDNESS_PRESETS[i].displayLabel
        }
        if (::meterAvg.isInitialized && ::meterRow.isInitialized) {
            applyOrientation(resources.configuration.orientation)
        }
        updateSettingsStateText()
    }

    /**
     * Settings panel opened by a 700 ms long-press on the meter screen.
     * Tap the return button or background area to return to full-screen meter.
     */
    private fun buildSettingsPanel() {
        settingsOverlay = ScrollView(this).apply {
            setBackgroundColor(Color.argb(242, 13, 13, 16))
            visibility = View.GONE
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            // Tapping empty space on the settings panel returns to the meter screen
            setOnClickListener { hideSettings() }
        }
        settingsOverlay.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            container.setPadding(
                dp(16) + bars.left, dp(14) + bars.top,
                dp(16) + bars.right, dp(24) + bars.bottom
            )
            insets
        }

        // Top row: Tap to return to full-screen meter + Start/Stop + Peak/Loudness Reset
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val closeButton = hwButton("← メーターへ戻る（タップで閉じる）", accent = true)
        closeButton.setOnClickListener { hideSettings() }
        topRow.addView(
            closeButton,
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )
        container.addView(topRow)

        // Measurement controls row (Start/Stop + Reset)
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ctrlLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10), 0, 0) }

        startButton = hwButton("測定開始", accent = true)
        startButton.setOnClickListener {
            if (running) stopCapture() else startCapture()
        }
        ctrlRow.addView(startButton, LinearLayout.LayoutParams(0, dp(46), 1f))

        resetButton = hwButton("ピーク / Integrated リセット")
        resetButton.setOnClickListener { resetPeaks() }
        val resetLp = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        }
        ctrlRow.addView(resetButton, resetLp)
        container.addView(ctrlRow, ctrlLp)

        fun sectionHeader(title: String): TextView = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.rgb(190, 182, 164))
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, dp(16), 0, dp(6))
        }

        // 1. Display Mode Switch: VU <-> Loudness
        container.addView(sectionHeader("表示モード切替（VU ⇔ ラウドネス）"))
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modeVuButton = hwButton("VU メーター")
        modeVuButton.setOnClickListener {
            displayMode = MeterView.DisplayMode.VU
            syncDisplayMode()
        }
        modeLoudnessButton = hwButton("ラウドネス (LUFS / I)")
        modeLoudnessButton.setOnClickListener {
            displayMode = MeterView.DisplayMode.LOUDNESS
            syncDisplayMode()
        }
        modeRow.addView(modeVuButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        val modeLoudLp = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        }
        modeRow.addView(modeLoudnessButton, modeLoudLp)
        container.addView(modeRow)

        // 2. Loudness Target Presets (9 presets)
        container.addView(sectionHeader("ラウドネス基準値プリセット（0 LU ターゲット）"))
        presetButtons.clear()
        for (i in LOUDNESS_PRESETS.indices) {
            val preset = LOUDNESS_PRESETS[i]
            val b = hwButton(preset.displayLabel).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    selectedPresetIndex = i
                    syncDisplayMode()
                }
            }
            presetButtons.add(b)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { setMargins(0, dp(4), 0, 0) }
            container.addView(b, lp)
        }

        // 3. VU Reference Level (0 VU = -24 .. -14 dBFS)
        container.addView(sectionHeader("VU 基準レベル（0 VU = dBFS）"))
        leverSwitch = LeverSwitchView(this).apply {
            values = listOf(-24.0, -20.0, -18.0, -16.0, -14.0)
            value = referenceDbfs
            onSelected = { v -> onRefSelected(v) }
        }
        container.addView(
            leverSwitch,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        )

        // 4. Input Device, Audio Engine (AudioRecord / AAudio), CSV Log
        container.addView(sectionHeader("入力デバイス・録音エンジン・ログ"))
        fun settingRow(b: Button) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { setMargins(0, dp(6), 0, 0) }
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            b.setPadding(dp(14), 0, dp(14), 0)
            container.addView(b, lp)
        }
        deviceButton = hwButton("入力: 自動")
        deviceButton.setOnClickListener { cycleDevice() }
        settingRow(deviceButton)

        refreshButton = hwButton("入力デバイス一覧を再読込")
        refreshButton.setOnClickListener { refreshDevices() }
        settingRow(refreshButton)

        engineButton = hwButton("エンジン: AudioRecord")
        engineButton.setOnClickListener { cycleEngine() }
        settingRow(engineButton)

        logButton = hwButton("CSVログ開始")
        logButton.setOnClickListener { toggleLog() }
        settingRow(logButton)

        // 5. Test Tone Generator
        container.addView(sectionHeader("テストトーン出力"))
        toneButton = hwButton("トーン OFF")
        toneModeButtons.clear()
        for (mode in TonePlayer.Mode.values()) {
            val b = hwButton(toneRowLabel(mode)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { setToneMode(mode) }
            }
            toneModeButtons[mode] = b
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { setMargins(0, dp(4), 0, 0) }
            container.addView(b, lp)
        }

        // 6. Operation manual
        container.addView(sectionHeader("操作マニュアル"))
        val manualText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(205, 205, 214))
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(4), 0, dp(8))
            text = """
                【メイン画面】
                タップ … VU ⇔ ラウドネスの切り替え
                ダブルタップ … 測定の開始／停止
                長押し（0.7秒）… 設定画面を開く
                LEDピークメーターをダブルタップ … ピーク／Integrated のリセット
                LEDメーター下の HOLD … ピークホールド表示のオン／オフ
                ランプ … 緑＝測定中、赤＝停止中

                【設定画面から戻る】
                戻るボタン／戻るジェスチャー、または「メーターへ戻る」

                【表示の見方】
                VU … 0 VU が基準レベル（既定 −18 dBFS）
                ラウドネス … 細針＝ショートターム（3秒）、太針＝Integrated、0 LU が選択中の基準値
                数値 … ピークはトゥルーピーク（dBTP）、LOUDNESS は M／S／I（LUFS）
            """.trimIndent()
        }
        container.addView(manualText)

        settingsOverlay.addView(container)
    }

    private fun showSettings() {
        settingsShown = true
        settingsOverlay.visibility = View.VISIBLE
        settingsOverlay.requestApplyInsets()
    }

    private fun hideSettings() {
        settingsShown = false
        settingsOverlay.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (settingsShown) hideSettings() else super.onBackPressed()
    }

    /**
     * VU mode: Portrait shows one meter (L+R sum), Landscape shows two meters (L and R separate).
     * Loudness mode: Always shows a single meter (meterAvg) regardless of orientation.
     */
    private fun applyOrientation(orientation: Int) {
        val landscape =
            orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // Landscape always shows the L / R pair (VU faces in VU mode,
        // per-channel loudness faces in loudness mode); portrait shows
        // the single combined L+R face.
        val showDual = landscape
        meterAvg.visibility = if (showDual) View.GONE else View.VISIBLE
        meterRow.visibility = if (showDual) View.VISIBLE else View.GONE
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
    }

    /** Dark metal button in the style of the meter chassis. */
    private fun hwButton(label: String, accent: Boolean = false, compact: Boolean = false): Button {
        val stroke = if (accent) Color.rgb(216, 164, 65) else Color.rgb(80, 80, 92)
        fun face(top: Int, bottom: Int) =
            android.graphics.drawable.GradientDrawable().apply {
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                setColors(intArrayOf(top, bottom))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), stroke)
            }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                face(Color.rgb(78, 78, 88), Color.rgb(60, 60, 68))
            )
            addState(intArrayOf(), face(Color.rgb(54, 54, 62), Color.rgb(37, 37, 43)))
        }
        return Button(this).apply {
            setSingleLine(true)
            text = label
            textSize = if (compact) 11.5f else 13.5f
            isAllCaps = false
            setTextColor(
                if (accent) Color.rgb(242, 201, 110) else Color.rgb(235, 235, 240)
            )
            background = states
            setPadding(dp(if (compact) 7 else 14), dp(if (compact) 6 else 8), dp(if (compact) 7 else 14), dp(if (compact) 6 else 8))
        }
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "本体マイク"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有線マイク"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_TELEPHONY -> "通話"
        else -> "入力"
    }

    private fun refreshDevices() {
        val selectedId = options.getOrNull(selectedDeviceIndex)?.device?.id
        val list = mutableListOf(InputOption("自動（既定の入力）", null))
        val allowed = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && it.type in allowed }
            .groupBy { d ->
                if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    "builtin:" + d.productName.toString()
                } else {
                    "usb:" + d.productName.toString() + "@" + (d.address ?: "")
                }
            }
            .values
            .map { group ->
                group.maxWithOrNull(
                    compareBy(
                        { if (it.channelCounts?.contains(2) == true) 1 else 0 },
                        { it.id }
                    )
                ) ?: group.first()
            }
            .sortedWith(compareBy({ devicePriority(it.type) }, { it.productName.toString() }))
        for (d in devices) {
            val name = d.productName?.toString()?.ifBlank { "不明なデバイス" } ?: "不明なデバイス"
            list.add(InputOption("$name（${typeLabel(d.type)}）", d))
        }
        options = list
        selectedDeviceIndex = if (selectedId != null) {
            val idx = list.indexOfFirst { it.device?.id == selectedId }
            if (idx >= 0) idx else 0
        } else {
            selectedDeviceIndex.coerceIn(0, maxOf(0, list.size - 1))
        }
        syncDeviceButtons()
    }

    private fun selectedOption(): InputOption? = options.getOrNull(selectedDeviceIndex)

    private fun devicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 0
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        else -> 3
    }

    private fun startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        stopCapture()
        dispTp[0] = -60.0
        dispTp[1] = -60.0
        lastTickMs = 0L
        if (useAAudio && startCaptureAAudio()) {
            return
        }
        startCaptureAudioRecord()
    }

    private fun startCaptureAAudio(): Boolean {
        val option = selectedOption()
        val cap = AAudioCapture()
        if (!cap.available) {
            statusText.text = "AAudioライブラリを読み込めなかったため、AudioRecordで開始します"
            return false
        }
        val presets = intArrayOf(
            AAudioCapture.PRESET_UNPROCESSED,
            AAudioCapture.PRESET_VOICE_RECOGNITION,
            AAudioCapture.PRESET_GENERIC
        )
        val presetNames = arrayOf("UNPROCESSED", "VOICE_RECOGNITION", "GENERIC")
        val deviceId = option?.device?.id ?: 0
        for (i in presets.indices) {
            val result = cap.start(deviceId, 48000, 2, presets[i]) ?: continue
            val rate = result[0]
            val ch = result[1]
            val eng = VuEngine(rate)
            eng.referenceDbfs = referenceDbfs
            engine = eng
            aaudio = cap
            sampleRate = rate
            channels = ch
            running = true
            captureThread = Thread { readLoopAAudio(cap) }.apply {
                name = "vu-capture-aaudio"
                start()
            }
            val devName = option?.device?.productName?.toString() ?: "既定の入力"
            statusText.text = "測定中［AAudio］: $devName / $rate Hz / " +
                (if (ch == 2) "ステレオ" else "モノラル") + " / " + presetNames[i]
            setStartButtons("測定停止")
            captureStartMs = android.os.SystemClock.elapsedRealtime()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            handler.post(uiLoop)
            return true
        }
        statusText.text = "AAudioを開始できなかったため、AudioRecordで開始します"
        return false
    }

    private fun readLoopAAudio(cap: AAudioCapture) {
        val eng = engine ?: return
        val ch = channels
        val fbuf = FloatArray(4096 * 2)
        while (running) {
            val frames = cap.read(fbuf, 4096)
            if (frames > 0) {
                eng.process(fbuf, frames, ch)
            } else if (frames < 0) {
                break
            }
        }
    }

    private fun startCaptureAudioRecord() {
        val option = selectedOption()
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        val rates = intArrayOf(48000, 44100)
        val channelConfigs = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        var started = false
        outer@ for (rate in rates) {
            for (chCfg in channelConfigs) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, chCfg, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                for (src in sources) {
                    try {
                        val rec = AudioRecord.Builder()
                            .setAudioSource(src)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(rate)
                                    .setChannelMask(chCfg)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build()
                            )
                            .setBufferSizeInBytes(minBuf * 4)
                            .build()
                        if (rec.state != AudioRecord.STATE_INITIALIZED) {
                            rec.release()
                            continue
                        }
                        if (option?.device != null) {
                            rec.preferredDevice = option.device
                        }
                        val eng = VuEngine(rate)
                        eng.referenceDbfs = referenceDbfs
                        engine = eng
                        recorder = rec
                        sampleRate = rate
                        channels = if (chCfg == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                        rec.startRecording()
                        running = true
                        captureThread = Thread { readLoop(rec, minBuf) }.apply {
                            name = "vu-capture"
                            start()
                        }
                        val routed = rec.routedDevice?.productName?.toString() ?: "不明"
                        val srcName = when (src) {
                            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
                            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                            else -> "MIC"
                        }
                        statusText.text = "測定中［AudioRecord］: $routed / $rate Hz / " +
                            (if (channels == 2) "ステレオ" else "モノラル") + " / $srcName"
                        started = true
                        break@outer
                    } catch (e: Exception) {
                        // try the next combination
                    }
                }
            }
        }
        if (started) {
            setStartButtons("測定停止")
            captureStartMs = android.os.SystemClock.elapsedRealtime()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            handler.post(uiLoop)
        } else {
            statusText.text = "入力を開始できませんでした。デバイスを確認してください。"
        }
    }

    private fun readLoop(rec: AudioRecord, minBuf: Int) {
        val sbuf = ShortArray(minBuf * 2)
        val fbuf = FloatArray(sbuf.size)
        val eng = engine ?: return
        val ch = channels
        while (running) {
            val n = rec.read(sbuf, 0, sbuf.size)
            if (n > 0) {
                for (i in 0 until n) fbuf[i] = sbuf[i] / 32768f
                eng.process(fbuf, n / ch, ch)
            } else if (n < 0) {
                break
            }
        }
    }

    private fun stopCapture() {
        if (!running && recorder == null && aaudio == null) return
        running = false
        handler.removeCallbacks(uiLoop)
        try {
            captureThread?.join(300)
        } catch (e: InterruptedException) {
            // ignore
        }
        captureThread = null
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // ignore
        }
        recorder?.release()
        recorder = null
        var aaNote = ""
        val cap = aaudio
        if (cap != null) {
            val xr = cap.xRunCount()
            val dr = cap.droppedFrames()
            cap.stop()
            aaudio = null
            if (xr > 0 || dr > 0) {
                aaNote = "（AAudio: XRun ${xr}回 / ドロップ ${dr}フレーム）"
            }
        }
        setStartButtons("測定開始")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (statusText.text?.startsWith("測定中") == true) {
            statusText.text = "停止しました" + aaNote
        }
        stopLogging()
    }

    private fun fmtLufs(x: Double): String =
        if (x.isNaN() || x <= -69.9) " --.-"
        else String.format(java.util.Locale.US, "%5.1f", x)

    private fun writeLogRow(e: VuEngine) {
        val logger = csvLogger ?: return
        val elapsed = (android.os.SystemClock.elapsedRealtime() - captureStartMs) / 1000.0
        val iso = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US
        ).format(java.util.Date())
        fun f(x: Double) =
            if (x.isNaN()) "" else String.format(java.util.Locale.US, "%.2f", x)
        logger.logRow(
            listOf(
                iso,
                String.format(java.util.Locale.US, "%.2f", elapsed),
                f(e.vu(0)), f(e.vu(1)),
                f(e.tpInstDbfs[0]), f(e.tpInstDbfs[1]),
                f(e.tpPeakDbfs(0)), f(e.tpPeakDbfs(1)),
                f(e.momentaryLufs()), f(e.shortTermLufs()), f(e.integratedLufs()),
                f(e.correlation()),
                if (e.clip[0]) "1" else "0",
                if (e.clip[1]) "1" else "0",
                referenceDbfs.toInt().toString()
            ).joinToString(",")
        )
    }

    private fun onRefSelected(v: Double) {
        referenceDbfs = v
        engine?.referenceDbfs = v
        tonePlayer.levelDbfs = v
        setRefLabels()
    }

    /** Resets held peaks, clip lamps, and integrated loudness accumulation. */
    private fun resetPeaks() {
        engine?.resetPeak()
        engine?.resetLoudness()
        meterAvg.peakLamp = false
        meterL.peakLamp = false
        meterR.peakLamp = false
        meterAvg.setLoudnessLevels(Double.NaN, Double.NaN)
        meterL.setLoudnessLevels(Double.NaN, Double.NaN)
        meterR.setLoudnessLevels(Double.NaN, Double.NaN)
    }

    private fun toneModeName(m: TonePlayer.Mode): String = when (m) {
        TonePlayer.Mode.HZ100 -> "100 Hz"
        TonePlayer.Mode.HZ1000 -> "1 kHz"
        TonePlayer.Mode.HZ10000 -> "10 kHz"
        TonePlayer.Mode.WHITE -> "ホワイトノイズ"
        TonePlayer.Mode.PINK -> "ピンクノイズ"
        TonePlayer.Mode.OFF -> ""
    }

    private fun toneRowLabel(m: TonePlayer.Mode): String =
        if (m == TonePlayer.Mode.OFF) "OFF（停止）" else toneModeName(m)

    private fun toneShortName(m: TonePlayer.Mode): String = when (m) {
        TonePlayer.Mode.HZ100 -> "100Hz"
        TonePlayer.Mode.HZ1000 -> "1kHz"
        TonePlayer.Mode.HZ10000 -> "10kHz"
        TonePlayer.Mode.WHITE -> "白色"
        TonePlayer.Mode.PINK -> "桃色"
        TonePlayer.Mode.OFF -> "OFF"
    }

    private fun updateToneButton() {
        if (!::toneButton.isInitialized) return
        val m = tonePlayer.mode
        toneButton.text = "トーン " + toneShortName(m)
        toneButton.setTextColor(
            if (m == TonePlayer.Mode.OFF) Color.rgb(235, 235, 240)
            else Color.rgb(242, 201, 110)
        )
        for ((mode, b) in toneModeButtons) {
            val active = mode == m
            b.setTextColor(
                if (active) Color.rgb(242, 201, 110) else Color.rgb(235, 235, 240)
            )
            b.text = (if (active) "● " else "   ") + toneRowLabel(mode)
        }
    }

    private fun setToneMode(mode: TonePlayer.Mode) {
        if (mode == TonePlayer.Mode.OFF) {
            tonePlayer.setMode(TonePlayer.Mode.OFF)
            updateToneButton()
            statusText.text = "トーンを停止しました"
            return
        }
        tonePlayer.levelDbfs = referenceDbfs
        val selectedName = selectedOption()?.device?.productName?.toString()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
        tonePlayer.preferredOutput = outputs.firstOrNull {
            selectedName != null && it.productName?.toString() == selectedName
        } ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        tonePlayer.setMode(mode)
        updateToneButton()
        val dest = tonePlayer.routedDeviceName() ?: "既定の出力"
        var msg = "${toneModeName(mode)}トーン出力中（${referenceDbfs.toInt()} dBFS）→ $dest"
        if (tonePlayer.preferredOutput == null) {
            msg += "\n注意: スピーカー出力の場合、本体マイク入力では音を拾ってハウリングすることがあります"
        }
        statusText.text = msg
    }

    private fun syncDeviceButtons() {
        val name = options.getOrNull(selectedDeviceIndex)
            ?.label?.substringBefore("（") ?: "自動"
        if (::deviceButton.isInitialized) deviceButton.text = "入力: $name"
    }

    private fun cycleDevice() {
        if (options.size <= 1) return
        selectedDeviceIndex = (selectedDeviceIndex + 1) % options.size
        syncDeviceButtons()
        if (running) startCapture()
    }

    private fun syncEngineButtons() {
        if (::engineButton.isInitialized) {
            engineButton.text = "エンジン: " + if (useAAudio) "AAudio" else "AudioRecord"
        }
    }

    private fun cycleEngine() {
        useAAudio = !useAAudio
        syncEngineButtons()
        if (running) startCapture()
    }

    private fun toggleLog() {
        if (logging) {
            stopLogging()
            return
        }
        if (!running) {
            statusText.text = "ログは測定中のみ記録できます。先に測定を開始してください。"
            return
        }
        val logger = csvLogger ?: CsvLogger(this).also { csvLogger = it }
        if (logger.start()) {
            logging = true
            logButton.text = "CSVログ停止"
            statusText.text = "ログ記録中: ${logger.savedLocation}"
        } else {
            statusText.text = "ログファイルを作成できませんでした"
        }
    }

    private fun stopLogging() {
        if (!logging) return
        logging = false
        val location = csvLogger?.savedLocation ?: ""
        csvLogger?.stop()
        logButton.text = "CSVログ開始"
        statusText.text = "ログを保存しました: $location"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCapture()
            } else {
                statusText.text = "マイクの許可が必要です。「設定」から許可してください。"
            }
        }
    }
}
