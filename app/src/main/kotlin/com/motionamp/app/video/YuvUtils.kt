// 130726 Initial implementation
package com.motionamp.app.video

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Conversions between YUV_420_888 Images and OpenCV Mats. Luma only is processed. */
object YuvUtils {

    /** Y plane -> new CV_32FC1 Mat (values 0..255). Caller releases. */
    fun lumaToMat(image: Image): Mat {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val bytes = ByteArray(w)
        val mat8 = Mat(h, w, CvType.CV_8UC1)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(bytes, 0, w)
            mat8.put(y, 0, bytes)
        }
        val mat32 = Mat()
        mat8.convertTo(mat32, CvType.CV_32FC1)
        mat8.release()
        return mat32
    }

    /** Clamped CV_32FC1 Mat -> Y plane of writable [dst]. Sizes must match. */
    fun writeLuma(luma: Mat, dst: Image) {
        val plane = dst.planes[0]
        val w = dst.width
        val h = dst.height
        require(luma.cols() == w && luma.rows() == h) { "luma size mismatch" }
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val mat8 = Mat()
        luma.convertTo(mat8, CvType.CV_8UC1)
        val bytes = ByteArray(w)
        for (y in 0 until h) {
            mat8.get(y, 0, bytes)
            buf.position(y * rowStride)
            buf.put(bytes, 0, w)
        }
        mat8.release()
    }

    /** Copy U and V planes from [src] to [dst], honouring each side's strides. */
    fun copyChroma(src: Image, dst: Image) {
        val cw = src.width / 2
        val ch = src.height / 2
        for (p in 1..2) {
            val sp = src.planes[p]
            val dp = dst.planes[p]
            val sBuf = sp.buffer
            val dBuf = dp.buffer
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    dBuf.put(y * dp.rowStride + x * dp.pixelStride,
                        sBuf.get(y * sp.rowStride + x * sp.pixelStride))
                }
            }
        }
    }
}
