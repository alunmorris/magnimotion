// 130726 Initial implementation
package com.motionamp.core

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Gaussian pyramid helpers. All returned Mats are owned by the caller. */
object GaussianPyramid {

    /** [levels] successively pyrDown-ed copies of [src]; result[0] is half resolution. */
    fun decompose(src: Mat, levels: Int): List<Mat> {
        require(levels >= 1) { "levels must be >= 1" }
        val out = ArrayList<Mat>(levels)
        var cur = src
        repeat(levels) {
            val down = Mat()
            Imgproc.pyrDown(cur, down)
            out.add(down)
            cur = down
        }
        return out
    }

    /**
     * Upsample [src] to exactly targetWidth x targetHeight: pyrUp doublings while
     * they fit, then one bilinear resize to absorb odd-size rounding.
     */
    fun upsampleTo(src: Mat, targetWidth: Int, targetHeight: Int): Mat {
        var cur = src.clone()
        while (cur.cols() * 2 <= targetWidth && cur.rows() * 2 <= targetHeight) {
            val up = Mat()
            Imgproc.pyrUp(cur, up)
            cur.release()
            cur = up
        }
        if (cur.cols() != targetWidth || cur.rows() != targetHeight) {
            val resized = Mat()
            Imgproc.resize(
                cur, resized,
                Size(targetWidth.toDouble(), targetHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR,
            )
            cur.release()
            cur = resized
        }
        return cur
    }
}
