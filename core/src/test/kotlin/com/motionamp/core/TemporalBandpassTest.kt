// 130726 Initial implementation
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.PI
import kotlin.math.sin

class TemporalBandpassTest {
    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** Push a scalar sequence through the filter via 1x1 Mats; return the scalar outputs. */
    private fun run(filter: TemporalBandpass, samples: DoubleArray): DoubleArray {
        val out = DoubleArray(samples.size)
        val frame = Mat(1, 1, CvType.CV_32FC1)
        for (i in samples.indices) {
            frame.put(0, 0, floatArrayOf(samples[i].toFloat()))
            val band = filter.filter(frame)
            out[i] = band.get(0, 0)[0]
            band.release()
        }
        frame.release()
        return out
    }

    /** Peak amplitude of the last [tail] samples. */
    private fun tailAmplitude(x: DoubleArray, tail: Int): Double {
        val t = x.takeLast(tail)
        return (t.max() - t.min()) / 2.0
    }

    private fun sine(freqHz: Double, fps: Double, n: Int, amplitude: Double) =
        DoubleArray(n) { amplitude * sin(2.0 * PI * freqHz * it / fps) }

    @Test
    fun firstFrameReturnsZeros() {
        val f = TemporalBandpass(0.4, 8.0, 30.0)
        val out = run(f, doubleArrayOf(123.0))
        assertEquals(0.0, out[0], 1e-6)
        f.release()
    }

    @Test
    fun dcIsFullyRejected() {
        val f = TemporalBandpass(0.4, 8.0, 30.0)
        val out = run(f, DoubleArray(300) { 100.0 })
        assertTrue("DC leak: ${tailAmplitude(out, 200)}", tailAmplitude(out, 200) < 0.01)
        f.release()
    }

    @Test
    fun passbandSinePassesAt240Fps() {
        // 2 Hz is well inside 0.4-8 Hz; expect gain > 0.5 after settling.
        val f = TemporalBandpass(0.4, 8.0, 240.0)
        val out = run(f, sine(2.0, 240.0, 2400, 10.0))
        assertTrue("passband gain too low: ${tailAmplitude(out, 480)}", tailAmplitude(out, 480) > 5.0)
        f.release()
    }

    @Test
    fun highFrequencyIsAttenuatedAt240Fps() {
        // 100 Hz is far above the 8 Hz cutoff; first-order rolloff gives gain < 0.3.
        val f = TemporalBandpass(0.4, 8.0, 240.0)
        val out = run(f, sine(100.0, 240.0, 2400, 10.0))
        assertTrue("stopband gain too high: ${tailAmplitude(out, 480)}", tailAmplitude(out, 480) < 3.0)
        f.release()
    }
}
