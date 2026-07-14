// 130726 Initial implementation
// 140726 Fix: row-batched chroma copy (per-pixel JNI was ~1e9 calls per high-fps clip)
package com.motionamp.app.video

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Conversions between YUV_420_888 Images and OpenCV Mats. Luma only is processed. */
object YuvUtils {

    /** Y plane -> new CV_32FC1 Mat (values 0..255). Caller releases.
     *  Y plane pixelStride is assumed to be 1 (guaranteed in practice for YUV_420_888 luma). */
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

    /** Copy U and V planes from [src] to [dst], honouring each side's strides.
     *  Rows are copied in bulk; the per-pixel path only runs when a side is interleaved. */
    fun copyChroma(src: Image, dst: Image) {
        val cw = src.width / 2
        val ch = src.height / 2
        for (p in 1..2) {
            val sp = src.planes[p]
            val dp = dst.planes[p]
            val sBuf = sp.buffer
            val dBuf = dp.buffer
            if (sp.pixelStride == 1 && dp.pixelStride == 1) {
                val row = ByteArray(cw)
                for (y in 0 until ch) {
                    sBuf.position(y * sp.rowStride)
                    sBuf.get(row, 0, cw)
                    dBuf.position(y * dp.rowStride)
                    dBuf.put(row, 0, cw)
                }
            } else {
                // Interleaved (e.g. NV12-style) plane: bulk-read each row span, scatter in
                // a Java array, read-modify-write so the sibling plane's bytes survive.
                val sLen = (cw - 1) * sp.pixelStride + 1
                val dLen = (cw - 1) * dp.pixelStride + 1
                val sRow = ByteArray(sLen)
                val dRow = ByteArray(dLen)
                for (y in 0 until ch) {
                    sBuf.position(y * sp.rowStride)
                    sBuf.get(sRow, 0, sLen)
                    dBuf.position(y * dp.rowStride)
                    dBuf.get(dRow, 0, dLen)
                    for (x in 0 until cw) {
                        dRow[x * dp.pixelStride] = sRow[x * sp.pixelStride]
                    }
                    dBuf.position(y * dp.rowStride)
                    dBuf.put(dRow, 0, dLen)
                }
            }
        }
    }
}
