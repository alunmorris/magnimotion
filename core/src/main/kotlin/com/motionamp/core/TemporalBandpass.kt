// 130726 Initial implementation
package com.motionamp.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.math.PI
import kotlin.math.exp

/**
 * Streaming first-order IIR temporal band-pass over equally-sized CV_32FC1 frames.
 *
 * band[n] = lpFast[n] - lpSlow[n], where lp[n] = (1-r)*lp[n-1] + r*x[n]
 * and r = 1 - exp(-2*PI*fc / fps). Constant memory: only the two low-pass
 * state images are held, so a clip of any length streams through.
 *
 * Not thread-safe. Call [release] when done.
 */
class TemporalBandpass(lowCutoffHz: Double, highCutoffHz: Double, fps: Double) {
    init {
        require(lowCutoffHz < highCutoffHz) { "low cutoff must be below high cutoff" }
        require(fps > 0) { "fps must be positive" }
    }

    private val rSlow = 1.0 - exp(-2.0 * PI * lowCutoffHz / fps)
    private val rFast = 1.0 - exp(-2.0 * PI * highCutoffHz / fps)
    private var lpSlow: Mat? = null
    private var lpFast: Mat? = null

    /** Returns a new band-passed Mat (caller releases). First call returns zeros. */
    fun filter(frame: Mat): Mat {
        val slow = lpSlow
        val fast = lpFast
        val band = Mat()
        if (slow == null || fast == null) {
            lpSlow = frame.clone()
            lpFast = frame.clone()
            band.create(frame.rows(), frame.cols(), frame.type())
            band.setTo(Scalar(0.0))
            return band
        }
        Core.addWeighted(slow, 1.0 - rSlow, frame, rSlow, 0.0, slow)
        Core.addWeighted(fast, 1.0 - rFast, frame, rFast, 0.0, fast)
        Core.subtract(fast, slow, band)
        return band
    }

    fun release() {
        lpSlow?.release(); lpSlow = null
        lpFast?.release(); lpFast = null
    }
}
