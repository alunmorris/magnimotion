// 130726 Initial implementation
package com.motionamp.app.video

import com.motionamp.core.MotionAmplifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Decode raw.mp4 -> amplify luma per frame -> encode amplified.mp4 with stretched timestamps. */
class AmplifyVideoUseCase {

    data class Params(
        val inputPath: String,
        val outputPath: String,
        val captureFps: Int,
        val alpha: Double,
        val slowMotionFactor: Int,
    )

    suspend fun run(params: Params, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Default) {
            require(params.captureFps > 0 && params.slowMotionFactor >= 1) { "bad params" }
            val decoder = VideoDecoder(params.inputPath)
            val info = decoder.readInfo()
            val totalFrames =
                max(1L, info.durationUs * params.captureFps / 1_000_000L).toInt()
            val amplifier = MotionAmplifier(params.captureFps.toDouble(), params.alpha)
            // High-speed clips carry far more frames per second of footage.
            val bitRate = if (params.captureFps >= 120) 20_000_000 else 12_000_000
            var encoder: VideoEncoder? = null
            var frames = 0
            var succeeded = false
            try {
                decoder.decode { image, ptsUs ->
                    if (!isActive) return@decode false
                    val enc = encoder ?: VideoEncoder(
                        width = image.width,
                        height = image.height,
                        frameRate = max(1, params.captureFps / params.slowMotionFactor),
                        bitRate = bitRate,
                        outputPath = params.outputPath,
                        orientationDegrees = info.rotationDegrees,
                    ).also { encoder = it }
                    val luma = YuvUtils.lumaToMat(image)
                    try {
                        val amplified = amplifier.amplify(luma)
                        try {
                            enc.encodeFrame(ptsUs * params.slowMotionFactor) { dst ->
                                YuvUtils.writeLuma(amplified, dst)
                                YuvUtils.copyChroma(image, dst)
                            }
                        } finally {
                            amplified.release()
                        }
                    } finally {
                        luma.release()
                    }
                    frames++
                    onProgress(min(0.99f, frames.toFloat() / totalFrames))
                    true
                }
                ensureActive() // cancelled mid-decode: fall through to catch, not success
                val enc = encoder ?: error("no frames decoded from ${params.inputPath}")
                encoder = null // finish() tears the encoder down even on failure; never call it twice
                enc.finish()
                succeeded = true
                onProgress(1f)
            } catch (t: Throwable) {
                runCatching { encoder?.finish() }
                if (!succeeded) File(params.outputPath).delete()
                throw t
            } finally {
                amplifier.release()
            }
        }
}
