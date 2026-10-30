package jp.himawari.vumeter

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sin

/**
 * 1 kHz calibration tone generator. The tone is generated at exactly the
 * selected reference level so that a loopback or a downstream device can
 * be aligned to 0 VU.
 */
class TonePlayer {

    @Volatile
    var playing: Boolean = false
        private set

    /** Tone amplitude in dBFS; updated live when the reference changes. */
    @Volatile
    var levelDbfs: Double = -18.0

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start(preferredOutput: AudioDeviceInfo?): Boolean {
        stop()
        val sampleRate = 48000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }
        if (preferredOutput != null) {
            t.preferredDevice = preferredOutput
        }
        track = t
        playing = true
        t.play()
        thread = Thread {
            val frames = 1024
            val buf = ShortArray(frames * 2)
            var phase = 0.0
            val phaseInc = 2.0 * Math.PI * 1000.0 / sampleRate
            while (playing) {
                val amp = 10.0.pow(levelDbfs / 20.0) * 32767.0
                for (i in 0 until frames) {
                    val s = (sin(phase) * amp).toInt().toShort()
                    buf[i * 2] = s
                    buf[i * 2 + 1] = s
                    phase += phaseInc
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                }
                val written = t.write(buf, 0, buf.size)
                if (written < 0) break
            }
        }.apply {
            name = "vu-tone"
            start()
        }
        return true
    }

    fun stop() {
        if (!playing && track == null) return
        playing = false
        try {
            thread?.join(300)
        } catch (e: InterruptedException) {
            // ignore
        }
        thread = null
        try {
            track?.stop()
        } catch (e: Exception) {
            // ignore
        }
        track?.release()
        track = null
    }

    fun routedDeviceName(): String? = track?.routedDevice?.productName?.toString()
}

/**
 * CSV measurement log. Saved to the public Downloads folder via
 * MediaStore (no storage permission needed); falls back to the app's
 * external files directory.
 */
class CsvLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var uri: Uri? = null
    private var file: File? = null

    var savedLocation: String = ""
        private set

    val isOpen: Boolean
        get() = writer != null

    fun start(): Boolean {
        stop()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "vu_log_$stamp.csv"
        var out: OutputStream? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val u = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            if (u != null) {
                out = context.contentResolver.openOutputStream(u)
                if (out != null) {
                    uri = u
                    savedLocation = "ダウンロード/$name"
                }
            }
        } catch (e: Exception) {
            out = null
        }
        if (out == null) {
            try {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val f = File(dir, name)
                out = FileOutputStream(f)
                file = f
                savedLocation = f.absolutePath
            } catch (e: Exception) {
                return false
            }
        }
        val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
        writer = w
        w.write("time_iso,elapsed_s,vu_l,vu_r,peak_inst_l_dbfs,peak_inst_r_dbfs," +
            "peak_hold_l_dbfs,peak_hold_r_dbfs,momentary_lufs,shortterm_lufs," +
            "integrated_lufs,correlation,clip_l,clip_r,reference_dbfs\n")
        w.flush()
        return true
    }

    fun logRow(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            // ignore write errors; logging stops on next start/stop cycle
        }
    }

    fun stop() {
        val w = writer ?: return
        writer = null
        try {
            w.flush()
            w.close()
        } catch (e: Exception) {
            // ignore
        }
        val u = uri
        if (u != null) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(u, values, null, null)
            } catch (e: Exception) {
                // ignore
            }
            uri = null
        }
        file = null
    }
}
