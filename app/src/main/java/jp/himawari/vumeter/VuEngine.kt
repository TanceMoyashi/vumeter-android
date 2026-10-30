package jp.himawari.vumeter

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Measurement engine:
 *  - VU ballistics following IEC 60268-17 (300 ms to 99 %, 1.24 % overshoot,
 *    verified by simulation). Calibration: a sine at the reference level
 *    reads exactly 0 VU.
 *  - Loudness following ITU-R BS.1770: K-weighting (pre-filter + RLB,
 *    coefficients derived for the actual sample rate), momentary (400 ms),
 *    short-term (3 s) and gated integrated loudness.
 *  - Phase correlation between L and R over a 1 s window.
 *  - Sample peak with hold, and clip latching.
 */
class VuEngine(private val sampleRate: Int) {

    companion object {
        const val ZETA = 0.813
        const val OMEGA = 13.5 // rad/s
        const val MEAN_RECTIFIED_SINE = 0.63662 // 2 / pi
        const val LOUDNESS_OFFSET = -0.691
    }

    /** Reference level in dBFS that corresponds to 0 VU. */
    @Volatile
    var referenceDbfs: Double = -18.0

    private val dt = 1.0 / sampleRate

    // ---- VU ballistics state (per channel: deflection, velocity)
    private val y = DoubleArray(2)
    private val v = DoubleArray(2)

    // ---- Peak state
    val peakLin = DoubleArray(2)
    private val peakAgeSec = DoubleArray(2) { 99.0 }
    val instPeakDbfs = DoubleArray(2) { -60.0 }

    @Volatile
    var clip = BooleanArray(2)

    @Volatile
    var channels: Int = 2
        private set

    // ---- BS.1770 K-weighting filters (per channel: pre-filter then RLB)
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val out = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = out
            return out
        }
    }

    private val preFilter = Array(2) { makePreFilter(sampleRate) }
    private val rlbFilter = Array(2) { makeRlbFilter(sampleRate) }

    private fun makePreFilter(fs: Int): Biquad {
        val f0 = 1681.974450955533
        val g = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(Math.PI * f0 / fs)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.5)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / q + k * k) / a0
        )
    }

    private fun makeRlbFilter(fs: Int): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(Math.PI * f0 / fs)
        val den = 1.0 + k / q + k * k
        return Biquad(
            1.0, -2.0, 1.0,
            2.0 * (k * k - 1.0) / den,
            (1.0 - k / q + k * k) / den
        )
    }

    // ---- Loudness block accumulation (100 ms blocks)
    private val blockSamples = sampleRate / 10
    private var blockCount = 0
    private val blockSumSq = DoubleArray(2)

    /** Per-block summed channel energies zL + zR (weights 1.0 / 1.0). */
    private val blockZ = ArrayList<Double>()

    /** Ring of the most recent block energies for momentary / short-term. */
    private val recentZ = ArrayDeque<Double>()

    // ---- Correlation block accumulation
    private var corrLL = 0.0
    private var corrRR = 0.0
    private var corrLR = 0.0
    private val corrRing = ArrayDeque<DoubleArray>() // each: [ll, rr, lr]

    fun resetPeak() {
        peakLin[0] = 0.0
        peakLin[1] = 0.0
        peakAgeSec[0] = 99.0
        peakAgeSec[1] = 99.0
        clip[0] = false
        clip[1] = false
    }

    fun resetLoudness() {
        synchronized(blockZ) {
            blockZ.clear()
            recentZ.clear()
        }
        blockSumSq[0] = 0.0
        blockSumSq[1] = 0.0
        blockCount = 0
    }

    /** Process interleaved float samples in [-1, 1]. */
    fun process(buf: FloatArray, frames: Int, ch: Int) {
        channels = ch
        val w2 = OMEGA * OMEGA
        val damp = 2.0 * ZETA * OMEGA
        var blockPeak0 = 0.0
        var blockPeak1 = 0.0
        for (f in 0 until frames) {
            val x0 = buf[f * ch].toDouble()
            val a0 = if (x0 < 0) -x0 else x0
            if (a0 > blockPeak0) blockPeak0 = a0
            val acc0 = w2 * (a0 - y[0]) - damp * v[0]
            v[0] += acc0 * dt
            y[0] += v[0] * dt

            // K-weighted energy, channel 0
            val k0 = rlbFilter[0].process(preFilter[0].process(x0))
            blockSumSq[0] += k0 * k0

            var x1 = x0
            if (ch > 1) {
                x1 = buf[f * ch + 1].toDouble()
                val a1 = if (x1 < 0) -x1 else x1
                if (a1 > blockPeak1) blockPeak1 = a1
                val acc1 = w2 * (a1 - y[1]) - damp * v[1]
                v[1] += acc1 * dt
                y[1] += v[1] * dt
                val k1 = rlbFilter[1].process(preFilter[1].process(x1))
                blockSumSq[1] += k1 * k1
                corrLR += x0 * x1
            }
            corrLL += x0 * x0
            corrRR += x1 * x1

            blockCount++
            if (blockCount >= blockSamples) {
                finishBlock(ch)
            }
        }
        if (ch == 1) {
            y[1] = y[0]
            blockPeak1 = blockPeak0
        }
        instPeakDbfs[0] = toDbfs(blockPeak0)
        instPeakDbfs[1] = toDbfs(blockPeak1)
        updatePeak(0, blockPeak0, frames)
        updatePeak(1, blockPeak1, frames)
        if (blockPeak0 >= 0.999) clip[0] = true
        if (blockPeak1 >= 0.999) clip[1] = true
    }

    private fun finishBlock(ch: Int) {
        val z0 = blockSumSq[0] / blockSamples
        val z = if (ch > 1) z0 + blockSumSq[1] / blockSamples else z0
        synchronized(blockZ) {
            blockZ.add(z)
            recentZ.addLast(z)
            while (recentZ.size > 30) recentZ.removeFirst()
        }
        corrRing.addLast(doubleArrayOf(corrLL, corrRR, corrLR))
        while (corrRing.size > 10) corrRing.removeFirst()
        blockSumSq[0] = 0.0
        blockSumSq[1] = 0.0
        corrLL = 0.0
        corrRR = 0.0
        corrLR = 0.0
        blockCount = 0
    }

    private fun updatePeak(chIdx: Int, blockPeak: Double, frames: Int) {
        val blockSec = frames * dt
        if (blockPeak >= peakLin[chIdx]) {
            peakLin[chIdx] = blockPeak
            peakAgeSec[chIdx] = 0.0
        } else {
            peakAgeSec[chIdx] += blockSec
            if (peakAgeSec[chIdx] > 1.5) {
                // Fall at 20 dB per second after a 1.5 s hold.
                peakLin[chIdx] *= 10.0.pow(-blockSec)
                if (peakLin[chIdx] < 0.0001) peakLin[chIdx] = 0.0
            }
        }
    }

    private fun toDbfs(lin: Double): Double =
        if (lin <= 1e-7) -60.0 else (20.0 * log10(lin)).coerceIn(-60.0, 3.0)

    /** Current VU reading of a channel. */
    fun vu(chIdx: Int): Double {
        val refMean = MEAN_RECTIFIED_SINE * 10.0.pow(referenceDbfs / 20.0)
        val yy = y[chIdx]
        if (yy <= 1e-7) return -60.0
        return (20.0 * log10(yy / refMean)).coerceIn(-60.0, 12.0)
    }

    fun peakDbfs(chIdx: Int): Double = toDbfs(peakLin[chIdx])

    /**
     * Stereo average VU: the two channels' ballistic deflections are
     * averaged in the linear (voltage) domain, then read as VU.
     */
    fun vuAverage(): Double {
        val refMean = MEAN_RECTIFIED_SINE * 10.0.pow(referenceDbfs / 20.0)
        val avg = (y[0] + y[1]) / 2.0
        if (avg <= 1e-7) return -60.0
        return (20.0 * log10(avg / refMean)).coerceIn(-60.0, 12.0)
    }

    // ---- Loudness readouts (LUFS). Returns Double.NaN when not enough data.

    private fun loudnessOf(meanZ: Double): Double =
        if (meanZ <= 0.0) Double.NaN else LOUDNESS_OFFSET + 10.0 * log10(meanZ)

    /** Momentary loudness, 400 ms window. */
    fun momentaryLufs(): Double {
        val blocks: List<Double>
        synchronized(blockZ) { blocks = recentZ.toList() }
        if (blocks.size < 4) return Double.NaN
        val last = blocks.subList(blocks.size - 4, blocks.size)
        return loudnessOf(last.average())
    }

    /** Short-term loudness, 3 s window. */
    fun shortTermLufs(): Double {
        val blocks: List<Double>
        synchronized(blockZ) { blocks = recentZ.toList() }
        if (blocks.isEmpty()) return Double.NaN
        return loudnessOf(blocks.average())
    }

    /**
     * Integrated loudness with BS.1770 gating: absolute gate at -70 LUFS,
     * then a relative gate 10 LU below the ungated level.
     * (Uses the 100 ms measurement blocks; the standard's 400 ms gating
     * blocks with overlap are approximated by these blocks.)
     */
    fun integratedLufs(): Double {
        val all: List<Double>
        synchronized(blockZ) { all = blockZ.toList() }
        if (all.size < 10) return Double.NaN
        val absGateZ = 10.0.pow((-70.0 - LOUDNESS_OFFSET) / 10.0)
        val aboveAbs = all.filter { it > absGateZ }
        if (aboveAbs.isEmpty()) return Double.NaN
        val stage1 = loudnessOf(aboveAbs.average())
        if (stage1.isNaN()) return Double.NaN
        val relGateZ = 10.0.pow((stage1 - 10.0 - LOUDNESS_OFFSET) / 10.0)
        val aboveRel = aboveAbs.filter { it > relGateZ }
        if (aboveRel.isEmpty()) return Double.NaN
        return loudnessOf(aboveRel.average())
    }

    /** Phase correlation in [-1, +1] over the last second. NaN if silent. */
    fun correlation(): Double {
        if (channels < 2) return 1.0
        var ll = 0.0
        var rr = 0.0
        var lr = 0.0
        for (b in corrRing) {
            ll += b[0]; rr += b[1]; lr += b[2]
        }
        val den = sqrt(ll * rr)
        if (den <= 1e-12) return Double.NaN
        return (lr / den).coerceIn(-1.0, 1.0)
    }
}
