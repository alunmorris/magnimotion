// 130726 Initial implementation
package com.motionamp.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * Streaming linear Eulerian Video Magnification on luma frames.
 *
 * Per frame: 4-level Gaussian pyramid; Laplacian bands L[i] = G[i] - up(G[i+1])
 * at 1/2, 1/4 and 1/8 resolution each go through their own temporal band-pass
 * (0.4-8 Hz); the band-passed signals, scaled by the per-level gain, are
 * upsampled to full resolution and added back. The 1/16 residual is not
 * amplified (avoids whole-frame brightness flicker); the finest band gets
 * half gain because fine detail is dominated by sensor noise.
 *
 * Stateful (IIR filters): feed frames in order, one call per frame, constant
 * frame size. Not thread-safe.
 */
class MotionAmplifier(fps: Double, alpha: Double) {
    private val levels = EvmConstants.PYRAMID_LEVELS
    private val bands = levels - 1
    private val filters = List(bands) {
        TemporalBandpass(EvmConstants.LOW_CUTOFF_HZ, EvmConstants.HIGH_CUTOFF_HZ, fps)
    }
    private val levelGains = DoubleArray(bands) { i -> if (i == 0) alpha * 0.5 else alpha }

    /** Input CV_32FC1 luma (0..255). Returns a new clamped Mat; caller releases. */
    fun amplify(luma: Mat): Mat {
        require(luma.type() == CvType.CV_32FC1) { "expected CV_32FC1 luma" }
        val gauss = GaussianPyramid.decompose(luma, levels)
        val delta = Mat.zeros(luma.rows(), luma.cols(), CvType.CV_32FC1)
        for (i in 0 until bands) {
            val up = GaussianPyramid.upsampleTo(gauss[i + 1], gauss[i].cols(), gauss[i].rows())
            val lap = Mat()
            Core.subtract(gauss[i], up, lap)
            up.release()
            val band = filters[i].filter(lap)
            lap.release()
            val full = GaussianPyramid.upsampleTo(band, luma.cols(), luma.rows())
            band.release()
            Core.addWeighted(delta, 1.0, full, levelGains[i], 0.0, delta)
            full.release()
        }
        gauss.forEach { it.release() }
        val out = Mat()
        Core.add(luma, delta, out)
        delta.release()
        Core.max(out, Scalar(0.0), out)
        Core.min(out, Scalar(255.0), out)
        return out
    }

    fun release() = filters.forEach { it.release() }
}
