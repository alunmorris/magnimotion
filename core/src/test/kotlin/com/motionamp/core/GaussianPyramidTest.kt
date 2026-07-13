// 130726 Initial implementation
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

class GaussianPyramidTest {
    companion object {
        @BeforeClass @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** 64x64 horizontal linear gradient 0..252 — smooth, so pyramid ops nearly preserve it. */
    private fun gradient(): Mat {
        val m = Mat(64, 64, CvType.CV_32FC1)
        for (y in 0 until 64) for (x in 0 until 64) {
            m.put(y, x, floatArrayOf(4f * x))
        }
        return m
    }

    @Test
    fun decomposeHalvesSizesPerLevel() {
        val src = gradient()
        val levels = GaussianPyramid.decompose(src, 4)
        assertEquals(listOf(32, 16, 8, 4), levels.map { it.cols() })
        assertEquals(listOf(32, 16, 8, 4), levels.map { it.rows() })
        levels.forEach { it.release() }; src.release()
    }

    @Test
    fun upsampleToHitsExactTargetSize() {
        val src = gradient()
        val levels = GaussianPyramid.decompose(src, 3) // coarsest is 8x8
        val up = GaussianPyramid.upsampleTo(levels[2], 64, 64)
        assertEquals(64, up.cols()); assertEquals(64, up.rows())
        up.release(); levels.forEach { it.release() }; src.release()
    }

    @Test
    fun downUpRoundTripPreservesSmoothImage() {
        val src = gradient()
        val levels = GaussianPyramid.decompose(src, 1)
        val up = GaussianPyramid.upsampleTo(levels[0], 64, 64)
        val diff = Mat()
        Core.absdiff(src, up, diff)
        val meanErr = Core.mean(diff).`val`[0]
        assertTrue("round-trip mean error $meanErr", meanErr < 3.0)
        diff.release(); up.release(); levels.forEach { it.release() }; src.release()
    }

    @Test
    fun oddSizesUpsampleCleanly() {
        // 45-row case occurs in the real pipeline (720 -> 360 -> 180 -> 90 -> 45).
        val src = Mat(45, 80, CvType.CV_32FC1)
        src.setTo(org.opencv.core.Scalar(7.0))
        val up = GaussianPyramid.upsampleTo(src, 160, 90)
        assertEquals(160, up.cols()); assertEquals(90, up.rows())
        up.release(); src.release()
    }
}
