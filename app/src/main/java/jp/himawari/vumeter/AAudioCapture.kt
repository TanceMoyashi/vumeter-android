package jp.himawari.vumeter

/**
 * JNI wrapper for the AAudio capture engine (libaaudio_capture.so).
 * Samples are delivered as interleaved floats in -1..1, the same shape
 * the AudioRecord path feeds into VuEngine.
 */
class AAudioCapture {

    companion object {
        /** Preset codes understood by the native side. */
        const val PRESET_GENERIC = 0
        const val PRESET_VOICE_RECOGNITION = 1
        const val PRESET_UNPROCESSED = 2

        val libraryLoaded: Boolean = try {
            System.loadLibrary("aaudio_capture")
            true
        } catch (e: Throwable) {
            false
        }
    }

    val available: Boolean get() = libraryLoaded

    /**
     * Opens and starts the AAudio input stream.
     * @param deviceId AudioManager device id, or 0 for the default input.
     * @return intArrayOf(actualSampleRate, actualChannels), or null on failure.
     */
    fun start(deviceId: Int, sampleRate: Int, channels: Int, preset: Int): IntArray? {
        if (!libraryLoaded) return null
        return try {
            nativeStart(deviceId, sampleRate, channels, preset)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Copies up to [maxFrames] frames into [out] (interleaved).
     * Blocks up to ~60 ms waiting for data.
     * @return frames copied, 0 on timeout, -1 when the stream has ended.
     */
    fun read(out: FloatArray, maxFrames: Int): Int {
        return try {
            nativeRead(out, maxFrames)
        } catch (e: Throwable) {
            -1
        }
    }

    /** Underrun count reported by AAudio. Read before stop(). -1 if unknown. */
    fun xRunCount(): Int = try {
        nativeXRunCount()
    } catch (e: Throwable) {
        -1
    }

    /** Frames the ring buffer had to drop because the reader fell behind. */
    fun droppedFrames(): Long = try {
        nativeDroppedFrames()
    } catch (e: Throwable) {
        0
    }

    fun stop() {
        try {
            nativeStop()
        } catch (e: Throwable) {
            // ignore
        }
    }

    private external fun nativeStart(
        deviceId: Int, sampleRate: Int, channels: Int, preset: Int
    ): IntArray?

    private external fun nativeRead(out: FloatArray, maxFrames: Int): Int
    private external fun nativeStop()
    private external fun nativeXRunCount(): Int
    private external fun nativeDroppedFrames(): Long
}
