// 130726 Initial implementation
package com.motionamp.app.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat

/** Synchronous MP4 -> YUV_420_888 frame decoder (video track only). */
class VideoDecoder(private val inputPath: String) {

    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val rotationDegrees: Int,
    )

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        error("no video track in $inputPath")
    }

    fun readInfo(): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            val format = extractor.getTrackFormat(selectVideoTrack(extractor))
            return VideoInfo(
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) else 0L,
                rotationDegrees = if (format.containsKey(MediaFormat.KEY_ROTATION))
                    format.getInteger(MediaFormat.KEY_ROTATION) else 0,
            )
        } finally {
            extractor.release()
        }
    }

    /**
     * Decode every frame in order. [onFrame]'s Image is only valid during the
     * call. Return false from [onFrame] to abort early. Throws on codec errors.
     */
    fun decode(onFrame: (image: Image, ptsUs: Long) -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(inputPath)
            val track = selectVideoTrack(extractor)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var aborted = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0 && !aborted) {
                        val image = codec.getOutputImage(outIdx)
                        if (image != null) {
                            val keepGoing = try {
                                onFrame(image, info.presentationTimeUs)
                            } finally {
                                image.close()
                            }
                            if (!keepGoing) aborted = true
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || aborted) {
                        outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
