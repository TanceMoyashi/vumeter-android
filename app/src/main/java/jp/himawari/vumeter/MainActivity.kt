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
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private data class InputOption(val label: String, val device: AudioDeviceInfo?)

    private lateinit var audioManager: AudioManager
    private lateinit var meterAvg: MeterView
    private lateinit var meterRow: LinearLayout
    private lateinit var meterL: MeterView
    private lateinit var meterR: MeterView
    private lateinit var peakBar: PeakBarView
    private lateinit var deviceSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var valueText: TextView
    private lateinit var overL: TextView
    private lateinit var overR: TextView
    private lateinit var startButton: Button

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

    private lateinit var loudnessText: TextView
    private lateinit var corrView: CorrelationView
    private lateinit var corrText: TextView
    private lateinit var toneButton: Button
    private lateinit var logButton: Button
    private val tonePlayer = TonePlayer()
    private var csvLogger: CsvLogger? = null
    private var logging = false
    private var captureStartMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private val uiLoop = object : Runnable {
        override fun run() {
            val e = engine
            if (running && e != null) {
                meterAvg.setLevel(e.vuAverage())
                meterL.setLevel(e.vu(0))
                meterR.setLevel(e.vu(1))
                peakBar.setPeaks(
                    e.instPeakDbfs[0], e.instPeakDbfs[1],
                    e.peakDbfs(0), e.peakDbfs(1)
                )
                overL.setBackgroundColor(if (e.clip[0]) Color.rgb(224, 49, 49) else Color.rgb(52, 40, 40))
                overR.setBackgroundColor(if (e.clip[1]) Color.rgb(224, 49, 49) else Color.rgb(52, 40, 40))
                corrView.setCorrelation(e.correlation())
                tickCount++
                if (tickCount % 5 == 0) {
                    valueText.text = String.format(
                        "L  %6.1f VU   peak %6.1f dBFS\nR  %6.1f VU   peak %6.1f dBFS",
                        e.vu(0), e.peakDbfs(0), e.vu(1), e.peakDbfs(1)
                    )
                    loudnessText.text = "LOUDNESS   M " + fmtLufs(e.momentaryLufs()) +
                        "   S " + fmtLufs(e.shortTermLufs()) +
                        "   I " + fmtLufs(e.integratedLufs()) + " LUFS"
                    val c = e.correlation()
                    corrText.text = if (c.isNaN()) "相関  ---" else String.format("相関 %+.2f", c)
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
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    override fun onDestroy() {
        stopCapture()
        tonePlayer.stop()
        csvLogger?.stop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 16, 20))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val title = TextView(this).apply {
            text = "VU METER"
            textSize = 20f
            setTextColor(Color.rgb(240, 240, 245))
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        root.addView(title)

        meterAvg = MeterView(this).apply {
            channelLabel = "L+R"
        }
        root.addView(
            meterAvg,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        meterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        meterL = MeterView(this).apply {
            channelLabel = "L"
        }
        meterR = MeterView(this).apply {
            channelLabel = "R"
            needleColor = Color.rgb(192, 39, 45)
        }
        meterRow.addView(
            meterL,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        meterRow.addView(
            meterR,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(meterRow)

        peakBar = PeakBarView(this)
        root.addView(
            peakBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        )

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        valueText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(220, 220, 228))
            typeface = android.graphics.Typeface.MONOSPACE
            text = "L    --- VU   peak   --- dBFS\nR    --- VU   peak   --- dBFS"
        }
        infoRow.addView(valueText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        overL = overLamp("OVER L")
        overR = overLamp("OVER R")
        infoRow.addView(overL)
        infoRow.addView(overR)
        root.addView(infoRow)

        loudnessText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(220, 220, 228))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
            text = "LOUDNESS   M  --.-   S  --.-   I  --.- LUFS"
        }
        root.addView(loudnessText)

        val corrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        corrText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(220, 220, 228))
            typeface = android.graphics.Typeface.MONOSPACE
            text = "相関  ---"
        }
        corrRow.addView(corrText, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
        corrView = CorrelationView(this)
        corrRow.addView(corrView, LinearLayout.LayoutParams(0, dp(30), 1f))
        root.addView(corrRow)

        // Input device selection
        val deviceLabel = sectionLabel("入力デバイス")
        root.addView(deviceLabel)
        val deviceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        deviceSpinner = Spinner(this)
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (running) startCapture()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        deviceRow.addView(deviceSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val refreshButton = Button(this).apply { text = "再読込" }
        refreshButton.setOnClickListener { refreshDevices() }
        deviceRow.addView(refreshButton)
        root.addView(deviceRow)

        // Capture engine selection: AudioRecord (Java API) or AAudio (native, low latency)
        root.addView(sectionLabel("録音エンジン"))
        val engineGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbAudioRecord = RadioButton(this).apply {
            text = "AudioRecord"
            textSize = 15f
            setTextColor(Color.rgb(230, 230, 235))
        }
        val rbAAudio = RadioButton(this).apply {
            text = "AAudio"
            textSize = 15f
            setTextColor(Color.rgb(230, 230, 235))
        }
        engineGroup.addView(rbAudioRecord)
        engineGroup.addView(rbAAudio)
        engineGroup.check(rbAudioRecord.id)
        engineGroup.setOnCheckedChangeListener { _, checkedId ->
            useAAudio = checkedId == rbAAudio.id
            if (running) startCapture()
        }
        root.addView(engineGroup)

        // Reference level selection
        root.addView(sectionLabel("基準レベル（0 VU）"))
        val refGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val refValues = listOf(-24.0, -20.0, -18.0, -16.0, -14.0)
        for (value in refValues) {
            val rb = RadioButton(this).apply {
                text = "${value.toInt()}"
                textSize = 15f
                setTextColor(Color.rgb(230, 230, 235))
                tag = value
            }
            refGroup.addView(rb)
            if (value == referenceDbfs) refGroup.check(rb.id)
        }
        refGroup.setOnCheckedChangeListener { group, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId)
            val v = rb.tag as Double
            referenceDbfs = v
            engine?.referenceDbfs = v
            tonePlayer.levelDbfs = v
            setRefLabels()
        }
        root.addView(refGroup)
        val unitLabel = TextView(this).apply {
            text = "単位: dBFS"
            textSize = 11f
            setTextColor(Color.rgb(150, 150, 158))
        }
        root.addView(unitLabel)

        // Buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        startButton = Button(this).apply { text = "測定開始" }
        startButton.setOnClickListener {
            if (running) stopCapture() else startCapture()
        }
        buttonRow.addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        toneButton = Button(this).apply { text = "トーン OFF" }
        toneButton.setOnClickListener { toggleTone() }
        buttonRow.addView(toneButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttonRow)

        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val resetButton = Button(this).apply { text = "ピークリセット" }
        resetButton.setOnClickListener {
            engine?.resetPeak()
            engine?.resetLoudness()
        }
        buttonRow2.addView(resetButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logButton = Button(this).apply { text = "ログ開始" }
        logButton.setOnClickListener { toggleLog() }
        buttonRow2.addView(logButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttonRow2)

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(160, 160, 168))
            setPadding(0, dp(8), 0, 0)
            text = "入力を選んで「測定開始」を押してください"
        }
        root.addView(statusText)

        val note = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(110, 110, 118))
            setPadding(0, dp(4), 0, 0)
            text = "注: 本体マイクは端末側の音声処理の影響を受けるため、レベル精度が必要な測定にはUSBオーディオI/Fの使用を推奨します。ラウドネスはITU-R BS.1770方式（ゲーティングは100msブロックによる近似）です。「ピークリセット」はラウドネスの積算もリセットします。"
        }
        root.addView(note)

        setRefLabels()
        applyOrientation(resources.configuration.orientation)
        val scroll = android.widget.ScrollView(this)
        scroll.addView(
            root,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)
    }

    private fun setRefLabels() {
        val label = "0 VU = ${referenceDbfs.toInt()} dBFS"
        meterAvg.refLabel = label
        meterL.refLabel = label
        meterR.refLabel = label
    }

    /** Portrait: one meter with the stereo average. Landscape: two meters. */
    private fun applyOrientation(orientation: Int) {
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            meterAvg.visibility = View.GONE
            meterRow.visibility = View.VISIBLE
        } else {
            meterAvg.visibility = View.VISIBLE
            meterRow.visibility = View.GONE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation)
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(170, 170, 178))
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun overLamp(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(52, 40, 40))
        setPadding(dp(8), dp(4), dp(8), dp(4))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(4), 0, 0, 0)
        layoutParams = lp
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
        val selectedId = options.getOrNull(deviceSpinner.selectedItemPosition)?.device?.id
        val list = mutableListOf(InputOption("自動（既定の入力）", null))
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .sortedWith(compareBy({ devicePriority(it.type) }, { it.productName.toString() }))
        for (d in devices) {
            val name = d.productName?.toString()?.ifBlank { "不明なデバイス" } ?: "不明なデバイス"
            list.add(InputOption("$name（${typeLabel(d.type)}）", d))
        }
        options = list
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            list.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
        if (selectedId != null) {
            val idx = list.indexOfFirst { it.device?.id == selectedId }
            if (idx >= 0) deviceSpinner.setSelection(idx)
        }
    }

    private fun devicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 0
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        else -> 3
    }

    private fun startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        stopCapture()
        if (useAAudio && startCaptureAAudio()) {
            return
        }
        startCaptureAudioRecord()
    }

    private fun startCaptureAAudio(): Boolean {
        val option = options.getOrNull(deviceSpinner.selectedItemPosition)
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
            startButton.text = "停止"
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
        val option = options.getOrNull(deviceSpinner.selectedItemPosition)
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
            startButton.text = "停止"
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
        startButton.text = "測定開始"
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
                f(e.instPeakDbfs[0]), f(e.instPeakDbfs[1]),
                f(e.peakDbfs(0)), f(e.peakDbfs(1)),
                f(e.momentaryLufs()), f(e.shortTermLufs()), f(e.integratedLufs()),
                f(e.correlation()),
                if (e.clip[0]) "1" else "0",
                if (e.clip[1]) "1" else "0",
                referenceDbfs.toInt().toString()
            ).joinToString(",")
        )
    }

    private fun toggleTone() {
        if (tonePlayer.playing) {
            tonePlayer.stop()
            toneButton.text = "トーン OFF"
            statusText.text = "トーンを停止しました"
            return
        }
        tonePlayer.levelDbfs = referenceDbfs
        val selectedName = options.getOrNull(deviceSpinner.selectedItemPosition)
            ?.device?.productName?.toString()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
        val preferred = outputs.firstOrNull {
            selectedName != null && it.productName?.toString() == selectedName
        } ?: outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (tonePlayer.start(preferred)) {
            toneButton.text = "トーン ON"
            val dest = tonePlayer.routedDeviceName() ?: "既定の出力"
            var msg = "1 kHzトーン出力中（${referenceDbfs.toInt()} dBFS）→ $dest"
            if (preferred == null) {
                msg += "\n注意: スピーカー出力の場合、本体マイク入力では音を拾ってハウリングすることがあります"
            }
            statusText.text = msg
        } else {
            statusText.text = "トーンを開始できませんでした"
        }
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
            logButton.text = "ログ停止"
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
        logButton.text = "ログ開始"
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
