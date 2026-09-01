// 130726 Initial implementation
// 140726 Fix: queue full input-buffer capacity so stride-padded planes are covered
package com.motionamp.app.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer

private const val BUILD_TAG = "3cbcd331-fbf8-4468-858d-76be1b4e8aef"

/**
 * Synchronous YUV -> H.264 MP4 encoder. Slow motion is baked in by the caller
 * passing pre-stretched [encodeFrame] timestamps; the muxer just writes them.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    frameRate: Int,
    bitRate: Int,
    outputPath: String,
    orientationDegrees: Int,
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun toString(): String = "VideoEncoder(${width}x$height, build=$BUILD_TAG)"

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var m: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            m = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (orientationDegrees != 0) m.setOrientationHint(orientationDegrees)
            codec.start()
            muxer = m
        } catch (t: Throwable) {
            runCatching { m?.release() }
            runCatching { codec.release() }
            throw t
        }
    }

    /** Blocks for a free input buffer, lets [fill] write its YUV Image, queues it. */
    fun encodeFrame(ptsUs: Long, fill: (Image) -> Unit) {
        var inIdx = -1
        while (inIdx < 0) {
            inIdx = codec.dequeueInputBuffer(10_000)
            drainOutput(untilEos = false)
        }
        // Real extent of this input buffer (incl. stride padding); width*height*3/2
        // under-reports on devices that pad plane rows. Read capacity before
        // getInputImage invalidates the ByteBuffer view.
        val bufferSize = codec.getInputBuffer(inIdx)?.capacity() ?: (width * height * 3 / 2)
        val image = codec.getInputImage(inIdx) ?: error("encoder input image unavailable")
        fill(image)
        codec.queueInputBuffer(inIdx, 0, bufferSize, ptsUs, 0)
        drainOutput(untilEos = false)
    }

    /** Send EOS, drain everything, close codec and muxer. Call exactly once.
     *  The encoder must not be reused after any exception. */
    fun finish() {
        try {
            var inIdx = -1
            while (inIdx < 0) {
                inIdx = codec.dequeueInputBuffer(10_000)
                drainOutput(untilEos = false)
            }
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainOutput(untilEos = true)
            codec.stop()
            if (muxerStarted) muxer.stop()
        } finally {
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }
    }

    private fun drainOutput(untilEos: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, if (untilEos) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (bufferInfo.size > 0 && !isConfig) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        muxer.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) return
                }
                else -> if (!untilEos) return // INFO_TRY_AGAIN_LATER; keep looping if draining to EOS
            }
        }
    }
}
