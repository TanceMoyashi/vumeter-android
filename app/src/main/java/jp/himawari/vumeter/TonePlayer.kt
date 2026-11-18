package jp.himawari.vumeter

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sin

/**
 * Test-signal generator: sine tones (100 Hz / 1 kHz / 10 kHz) plus white
 * and pink noise, played at the reference level. The sine level is the
 * peak level; the noise buffers are RMS-normalised, so the noise level is
 * the RMS level. Output follows the system route (speaker, USB audio
 * interface, ...), like any media playback.
 */
class TonePlayer {
    enum class Mode { OFF, HZ100, HZ1000, HZ10000, WHITE, PINK }

    var mode: Mode = Mode.OFF
        private set
    var levelDbfs: Double = -18.0

    /** Optional output route (e.g. the USB interface paired with the
     *  selected input). Null keeps the system default route. */
    var preferredOutput: AudioDeviceInfo? = null

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var playing = false

    val isPlaying: Boolean get() = playing

    fun routedDeviceName(): String? = try {
        track?.routedDevice?.productName?.toString()
    } catch (_: Exception) {
        null
    }

    fun nextMode(): Mode {
        val all = Mode.values()
        return all[(mode.ordinal + 1) % all.size]
    }

    fun setMode(newMode: Mode) {
        stop()
        mode = newMode
        if (newMode != Mode.OFF) start()
    }

    private fun start() {
        val sampleRate = 48000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        preferredOutput?.let { t.preferredDevice = it }
        playing = true
        val freq = when (mode) {
            Mode.HZ100 -> 100.0
            Mode.HZ1000 -> 1000.0
            Mode.HZ10000 -> 10000.0
            else -> 0.0
        }
        val noise = when (mode) {
            Mode.WHITE -> whiteBuffer(sampleRate)
            Mode.PINK -> pinkBuffer(sampleRate)
            else -> null
        }
        thread = Thread({
            val frames = 1024
            val buf = ShortArray(frames * 2)
            var phase = 0.0
            val phaseInc = 2.0 * Math.PI * freq / sampleRate
            var nIdx = 0
            t.play()
            while (playing) {
                val gain = Math.pow(10.0, levelDbfs / 20.0)
                for (i in 0 until frames) {
                    val s: Double = if (noise != null) {
                        val v = noise[nIdx].toDouble() * gain
                        nIdx = (nIdx + 1) % noise.size
                        v
                    } else {
                        val v = sin(phase) * gain
                        phase += phaseInc
                        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                        v
                    }
                    val pcm = (s.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                    buf[i * 2] = pcm
                    buf[i * 2 + 1] = pcm
                }
                var off = 0
                while (off < buf.size && playing) {
                    val n = t.write(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
            }
        }, "TonePlayer").also { it.start() }
    }

    fun stop() {
        playing = false
        try {
            thread?.join(300)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            track?.stop()
        } catch (_: IllegalStateException) {
        }
        track?.release()
        track = null
        mode = Mode.OFF
    }

    companion object {
        private var whiteBuf: FloatArray? = null
        private var pinkBuf: FloatArray? = null

        @Synchronized
        private fun whiteBuffer(sampleRate: Int): FloatArray {
            whiteBuf?.let { return it }
            val rnd = java.util.Random(42)
            val buf = FloatArray(sampleRate * 4) {
                (rnd.nextDouble() * 2.0 - 1.0).toFloat()
            }
            normalize(buf)
            whiteBuf = buf
            return buf
        }

        @Synchronized
        private fun pinkBuffer(sampleRate: Int): FloatArray {
            pinkBuf?.let { return it }
            val rnd = java.util.Random(1234)
            val buf = FloatArray(sampleRate * 4)
            var b0 = 0.0
            var b1 = 0.0
            var b2 = 0.0
            var b3 = 0.0
            var b4 = 0.0
            var b5 = 0.0
            var b6 = 0.0
            for (i in buf.indices) {
                val white = rnd.nextDouble() * 2.0 - 1.0
                b0 = 0.99886 * b0 + white * 0.0555179
                b1 = 0.99332 * b1 + white * 0.0750759
                b2 = 0.96900 * b2 + white * 0.1538520
                b3 = 0.86650 * b3 + white * 0.3104856
                b4 = 0.55000 * b4 + white * 0.5329522
                b5 = -0.7616 * b5 - white * 0.0168980
                val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
                b6 = white * 0.115926
                buf[i] = (pink * 0.11).toFloat()
            }
            normalize(buf)
            pinkBuf = buf
            return buf
        }

        /** Scale so the RMS is 1.0, then limit the peak to 0.99. */
        private fun normalize(buf: FloatArray) {
            var sum = 0.0
            for (v in buf) sum += v.toDouble() * v
            val rms = Math.sqrt(sum / buf.size)
            if (rms <= 0.0) return
            var peak = 0.0
            for (i in buf.indices) {
                buf[i] = (buf[i] / rms).toFloat()
                val a = kotlin.math.abs(buf[i])
                if (a > peak) peak = a.toDouble()
            }
            if (peak > 0.99) {
                val k = (0.99 / peak).toFloat()
                for (i in buf.indices) buf[i] *= k
            }
        }
    }
}
