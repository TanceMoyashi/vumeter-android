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
