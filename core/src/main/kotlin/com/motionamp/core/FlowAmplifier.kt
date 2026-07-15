// 140726 Initial implementation — optical-flow warping replaces the EVM amplifier
// 150726 setReference: allow a preset rest pose (clip middle frame) instead of the first frame
// 150726 Farneback → DIS optical flow (ULTRAFAST preset): several times faster per frame
// 150726 Fix: smooth flow (spatial propagation + Gaussian) — DIS patch edges became blocks at high gain
package com.motionamp.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.DISOpticalFlow

/**
 * Motion exaggeration by optical-flow warping (Lagrangian magnification).
 *
 * Dense DIS optical flow is computed from the reference frame (the rest pose) to
 * the current frame at a reduced analysis resolution. The mean flow is subtracted so
 * whole-frame drift (hand shake, panning) is not exaggerated. Each output pixel
 * then samples the current frame at x − (α−1)·flow(x), so a part displaced by d
 * from its rest position appears displaced by α·d.
 *
 * Stateful: the first frame becomes the reference; feed frames in order at a
 * constant size. Not thread-safe. Call [release] when done.
 */
class FlowAmplifier(private val alpha: Double) {

    /** Backward-remap tables; chroma maps are at half resolution for YUV420 planes. */
    class WarpMaps(
        val lumaMapX: Mat,
        val lumaMapY: Mat,
        val chromaMapX: Mat,
        val chromaMapY: Mat,
    ) {
        fun release() {
            lumaMapX.release(); lumaMapY.release()
            chromaMapX.release(); chromaMapY.release()
        }
    }

    private var reference: Mat? = null // analysis-resolution CV_8UC1 rest pose

    // DIS ultrafast is several times quicker than Farneback at comparable quality.
    // Spatial propagation smooths its coarse patch grid at almost no cost.
    private val dis: DISOpticalFlow =
        DISOpticalFlow.create(DISOpticalFlow.PRESET_ULTRAFAST).apply {
            useSpatialPropagation = true
        }

    /**
     * Use [luma] (full resolution) as the rest-pose reference instead of the first
     * streamed frame — e.g. the clip's middle frame, so oscillating motion is
     * exaggerated symmetrically around its centre. [luma] stays owned by the caller.
     */
    fun setReference(luma: Mat) {
        reference?.release()
        reference = toAnalysis(luma)
    }
    private var gridX: Mat? = null     // cached identity grids, full resolution
    private var gridY: Mat? = null
    private var gridXc: Mat? = null    // cached identity grids, chroma resolution
    private var gridYc: Mat? = null

    /**
     * Compute warp maps for a full-resolution CV_32FC1 (or CV_8UC1) luma frame.
     * Returns null for the first (reference) frame — pass that frame through unchanged.
     * Caller releases the returned maps; [luma] is untouched.
     */
    fun computeMaps(luma: Mat): WarpMaps? {
        val analysis = toAnalysis(luma)
        val ref = reference
        if (ref == null) {
            reference = analysis
            return null
        }
        val flow = Mat()
        dis.calc(ref, analysis, flow)
        analysis.release()
        // DIS works in ~8 px patches; at high gain the (α−1) multiplier turns patch-edge
        // discontinuities into visible blocks. Smooth the field before scaling.
        Imgproc.GaussianBlur(flow, flow, Size(21.0, 21.0), 0.0)

        // Whole-frame drift (hand shake / pan) must not be exaggerated.
        val mean = Core.mean(flow)
        Core.subtract(flow, Scalar(mean.`val`[0], mean.`val`[1]), flow)

        val w = luma.cols()
        val h = luma.rows()
        val cw = w / 2
        val ch = h / 2
        // Flow values are in analysis pixels; rescale to each target's pixel units.
        val valueScale = w.toDouble() / analysisWidthFor(w)
        ensureGrids(w, h, cw, ch)

        val (lumaX, lumaY) = mapsFor(flow, w, h, valueScale, gridX!!, gridY!!)
        val (chromaX, chromaY) = mapsFor(flow, cw, ch, valueScale / 2.0, gridXc!!, gridYc!!)
        flow.release()
        return WarpMaps(lumaX, lumaY, chromaX, chromaY)
    }

    fun release() {
        reference?.release(); reference = null
        gridX?.release(); gridY?.release(); gridXc?.release(); gridYc?.release()
        gridX = null; gridY = null; gridXc = null; gridYc = null
    }

    /** Resize the flow field to (w, h), scale its values, and build backward maps. */
    private fun mapsFor(
        flow: Mat,
        w: Int,
        h: Int,
        valueScale: Double,
        gx: Mat,
        gy: Mat,
    ): Pair<Mat, Mat> {
        val resized = Mat()
        Imgproc.resize(flow, resized, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val channels = ArrayList<Mat>(2)
        Core.split(resized, channels)
        resized.release()
        val mapX = Mat()
        val mapY = Mat()
        // map = grid − (α−1)·flow: sampling backwards displaces content forwards to α·d.
        Core.addWeighted(gx, 1.0, channels[0], -(alpha - 1.0) * valueScale, 0.0, mapX)
        Core.addWeighted(gy, 1.0, channels[1], -(alpha - 1.0) * valueScale, 0.0, mapY)
        channels.forEach { it.release() }
        return mapX to mapY
    }

    /** Downscale to the analysis width (never upscale) as CV_8UC1 for Farneback. */
    private fun toAnalysis(luma: Mat): Mat {
        val m8 = Mat()
        if (luma.type() == CvType.CV_8UC1) luma.copyTo(m8) else luma.convertTo(m8, CvType.CV_8UC1)
        val aw = analysisWidthFor(luma.cols())
        if (aw == luma.cols()) return m8
        val ah = luma.rows() * aw / luma.cols()
        val resized = Mat()
        Imgproc.resize(m8, resized, Size(aw.toDouble(), ah.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        m8.release()
        return resized
    }

    private fun analysisWidthFor(fullWidth: Int) = minOf(fullWidth, ANALYSIS_WIDTH)

    private fun ensureGrids(w: Int, h: Int, cw: Int, ch: Int) {
        if (gridX != null) return
        val (fx, fy) = identityGrid(w, h)
        val (cx, cy) = identityGrid(cw, ch)
        gridX = fx; gridY = fy; gridXc = cx; gridYc = cy
    }

    private fun identityGrid(w: Int, h: Int): Pair<Mat, Mat> {
        val gx = Mat(h, w, CvType.CV_32FC1)
        val gy = Mat(h, w, CvType.CV_32FC1)
        val rowX = FloatArray(w) { it.toFloat() }
        val rowY = FloatArray(w)
        for (y in 0 until h) {
            gx.put(y, 0, rowX)
            java.util.Arrays.fill(rowY, y.toFloat())
            gy.put(y, 0, rowY)
        }
        return gx to gy
    }

    companion object {
        /** Flow analysis resolution: quality/speed knob (720p luma → 640-wide analysis). */
        const val ANALYSIS_WIDTH = 640

        /** Warp [src] (any single-channel type) through backward maps. Caller releases. */
        fun warp(src: Mat, mapX: Mat, mapY: Mat): Mat {
            val out = Mat()
            Imgproc.remap(src, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, Scalar(0.0))
            return out
        }
    }
}
