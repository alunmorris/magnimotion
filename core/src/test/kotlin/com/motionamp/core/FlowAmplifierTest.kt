// 140726 Initial implementation
// 160726 Added sub-pixel noise-gate test
package com.motionamp.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.exp

class FlowAmplifierTest {
    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** 160x120 CV_32FC1: background 30 with a Gaussian blob (sigma 6, peak +200) at (cx, cy). */
    private fun blobFrame(cx: Double, cy: Double): Mat {
        val m = Mat(120, 160, CvType.CV_32FC1)
        val row = FloatArray(160)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val dx = x - cx
                val dy = y - cy
                row[x] = (30.0 + 200.0 * exp(-(dx * dx + dy * dy) / (2.0 * 6.0 * 6.0))).toFloat()
            }
            m.put(y, 0, row)
        }
        return m
    }

    /** Intensity-weighted centroid x of (frame - background). */
    private fun centroidX(m: Mat): Double {
        var sum = 0.0
        var sumX = 0.0
        val row = FloatArray(m.cols())
        for (y in 0 until m.rows()) {
            m.get(y, 0, row)
            for (x in row.indices) {
                val w = (row[x] - 30.0).coerceAtLeast(0.0)
                sum += w
                sumX += w * x
            }
        }
        return sumX / sum
    }

    @Test
    fun firstFrameHasNoMaps() {
        val amp = FlowAmplifier(5.0)
        val f0 = blobFrame(60.0, 60.0)
        assertNull(amp.computeMaps(f0))
        f0.release(); amp.release()
    }

    @Test
    fun staticSceneIsUnchanged() {
        val amp = FlowAmplifier(15.0)
        val f0 = blobFrame(60.0, 60.0)
        assertNull(amp.computeMaps(f0))
        val f1 = blobFrame(60.0, 60.0)
        val maps = amp.computeMaps(f1)!!
        val out = FlowAmplifier.warp(f1, maps.lumaMapX, maps.lumaMapY)
        val diff = Mat()
        Core.absdiff(f1, out, diff)
        val maxDiff = Core.minMaxLoc(diff).maxVal
        assertTrue("static frame changed by $maxDiff", maxDiff < 5.0)
        diff.release(); out.release(); maps.release(); f1.release(); f0.release(); amp.release()
    }

    @Test
    fun subPixelJitterIsNotAmplified() {
        // A 0.15 px shift is below the noise floor: at alpha 15 it must NOT become
        // a ~2 px blob displacement — the output should track the input.
        val amp = FlowAmplifier(15.0)
        val f0 = blobFrame(60.0, 60.0)
        assertNull(amp.computeMaps(f0))
        val f1 = blobFrame(60.15, 60.0)
        val maps = amp.computeMaps(f1)!!
        val out = FlowAmplifier.warp(f1, maps.lumaMapX, maps.lumaMapY)
        val outX = centroidX(out)
        assertTrue("sub-pixel jitter amplified: centroid $outX", outX < 61.0)
        out.release(); maps.release(); f1.release(); f0.release(); amp.release()
    }

    @Test
    fun smallMotionSurvivesTheNoiseGate() {
        // A 1 px shift is real motion: soft shrinkage must leave most of it, so at
        // alpha 15 the blob lands well beyond its unamplified position (~72 ideal).
        val amp = FlowAmplifier(15.0)
        val f0 = blobFrame(60.0, 60.0)
        assertNull(amp.computeMaps(f0))
        val f1 = blobFrame(61.0, 60.0)
        val maps = amp.computeMaps(f1)!!
        val out = FlowAmplifier.warp(f1, maps.lumaMapX, maps.lumaMapY)
        val outX = centroidX(out)
        assertTrue("small motion over-suppressed: centroid $outX", outX > 65.0)
        out.release(); maps.release(); f1.release(); f0.release(); amp.release()
    }

    @Test
    fun displacementIsAmplifiedByAlpha() {
        val alpha = 4.0
        val amp = FlowAmplifier(alpha)
        val f0 = blobFrame(60.0, 60.0)
        assertNull(amp.computeMaps(f0))
        val f1 = blobFrame(63.0, 60.0) // moved +3 px from rest
        val maps = amp.computeMaps(f1)!!
        val out = FlowAmplifier.warp(f1, maps.lumaMapX, maps.lumaMapY)
        val inX = centroidX(f1)
        val outX = centroidX(out)
        // Expected: rest 60 + alpha*3 = 72; Farneback is approximate, so allow slack,
        // but the output must be clearly beyond the unamplified position.
        assertTrue("input centroid $inX should be near 63", inX > 62.0 && inX < 64.0)
        assertTrue("output centroid $outX should be near 72", outX > 68.0 && outX < 76.0)
        out.release(); maps.release(); f1.release(); f0.release(); amp.release()
    }

    @Test
    fun presetReferenceAmplifiesFromFirstFrame() {
        val amp = FlowAmplifier(4.0)
        val ref = blobFrame(60.0, 60.0)
        amp.setReference(ref)
        ref.release()
        val f1 = blobFrame(63.0, 60.0)
        val maps = amp.computeMaps(f1)
        assertTrue("maps should be available immediately after setReference", maps != null)
        val out = FlowAmplifier.warp(f1, maps!!.lumaMapX, maps.lumaMapY)
        val outX = centroidX(out)
        assertTrue("output centroid $outX should be near 72", outX > 68.0 && outX < 76.0)
        out.release(); maps.release(); f1.release(); amp.release()
    }

    @Test
    fun mapsMatchRequestedResolutions() {
        val amp = FlowAmplifier(5.0)
        val f0 = blobFrame(60.0, 60.0)
        assertNull(amp.computeMaps(f0))
        val f1 = blobFrame(61.0, 60.0)
        val maps = amp.computeMaps(f1)!!
        assertTrue(maps.lumaMapX.cols() == 160 && maps.lumaMapX.rows() == 120)
        assertTrue(maps.chromaMapX.cols() == 80 && maps.chromaMapX.rows() == 60)
        maps.release(); f1.release(); f0.release(); amp.release()
    }
}
