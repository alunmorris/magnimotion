// 130726 Initial implementation
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MotionAmplifierTest {
    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /**
     * 160x120 frame: background 50, a 200-valued square of half-width 20 centred at
     * (cx, 60), with 2-px soft (linear) edges so sub-pixel motion changes intensities.
     */
    private fun renderFrame(cx: Double): Mat {
        fun edge(d: Double) = (d / 2.0 + 0.5).coerceIn(0.0, 1.0)
        val m = Mat(120, 160, CvType.CV_32FC1)
        val row = FloatArray(160)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val cov = edge(x - (cx - 20.0)) * edge((cx + 20.0) - x) *
                    edge(y - 40.0) * edge(80.0 - y)
                row[x] = (50.0 + 150.0 * cov).toFloat()
            }
            m.put(y, 0, row)
        }
        return m
    }

    /** Temporal standard deviation of pixel (row,col) over the given frames. */
    private fun pixelStd(frames: List<Mat>, row: Int, col: Int): Double {
        val v = frames.map { it.get(row, col)[0] }
        val mean = v.average()
        return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
    }

    @Test
    fun staticSceneIsUnchanged() {
        val amp = MotionAmplifier(30.0, 15.0)
        val input = renderFrame(50.0)
        var last: Mat? = null
        repeat(30) {
            last?.release()
            last = amp.amplify(input)
        }
        val diff = Mat()
        Core.absdiff(input, last!!, diff)
        val maxDiff = Core.minMaxLoc(diff).maxVal
        assertTrue("static frame changed by $maxDiff", maxDiff < 0.01)
        diff.release(); input.release(); last!!.release(); amp.release()
    }

    @Test
    fun subPixelOscillationIsAmplified() {
        // 2 Hz, +/-0.5 px horizontal oscillation at 30 fps, alpha 15.
        val fps = 30.0
        val amp = MotionAmplifier(fps, 15.0)
        val inputs = ArrayList<Mat>()
        val outputs = ArrayList<Mat>()
        for (n in 0 until 90) {
            val cx = 50.0 + 0.5 * sin(2.0 * PI * 2.0 * n / fps)
            val frame = renderFrame(cx)
            inputs.add(frame)
            outputs.add(amp.amplify(frame))
        }
        // Pixel on the square's left edge (x = 30) where intensity slope is steepest.
        val inStd = pixelStd(inputs.subList(45, 90), 60, 30)
        val outStd = pixelStd(outputs.subList(45, 90), 60, 30)
        assertTrue("edge pixel input std $inStd should be > 5", inStd > 5.0)
        assertTrue("amplification ratio ${outStd / inStd} should exceed 2", outStd > inStd * 2.0)
        (inputs + outputs).forEach { it.release() }
        amp.release()
    }

    @Test
    fun outputStaysInValidRangeAndSize() {
        val amp = MotionAmplifier(30.0, 30.0)
        var out: Mat? = null
        for (n in 0 until 60) {
            out?.release()
            val f = renderFrame(50.0 + 2.0 * sin(2.0 * PI * 2.0 * n / 30.0))
            out = amp.amplify(f)
            f.release()
        }
        assertEquals(160, out!!.cols()); assertEquals(120, out!!.rows())
        val mm = Core.minMaxLoc(out)
        assertTrue("min ${mm.minVal}", mm.minVal >= 0.0)
        assertTrue("max ${mm.maxVal}", mm.maxVal <= 255.0)
        out!!.release(); amp.release()
    }
}
