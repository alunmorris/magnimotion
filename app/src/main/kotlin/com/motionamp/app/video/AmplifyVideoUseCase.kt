// 130726 Initial implementation
// 140726 Replaced EVM luma amplification with optical-flow warping (FlowAmplifier); chroma now warped too
// 150726 Rest-pose reference is now the clip's middle frame (symmetric exaggeration of oscillation)
package com.motionamp.app.video

import com.motionamp.core.FlowAmplifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Decode raw.mp4 -> exaggerate motion per frame -> encode amplified.mp4 with stretched timestamps. */
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
            val amplifier = FlowAmplifier(params.alpha)
            // Middle frame as the rest pose: oscillation is exaggerated symmetrically (+/- alpha*d).
            // If the seek pre-pass fails, the first streamed frame becomes the reference as before.
            runCatching { decoder.decodeLumaAt(info.durationUs / 2) }
                .onSuccess { ref ->
                    amplifier.setReference(ref)
                    ref.release()
                }
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
                        val maps = amplifier.computeMaps(luma)
                        if (maps == null) {
                            // First frame is the rest-pose reference: pass through unchanged.
                            enc.encodeFrame(ptsUs * params.slowMotionFactor) { dst ->
                                YuvUtils.writeLuma(luma, dst)
                                YuvUtils.copyChroma(image, dst)
                            }
                        } else {
                            try {
                                val warpedY = FlowAmplifier.warp(luma, maps.lumaMapX, maps.lumaMapY)
                                val (u, v) = YuvUtils.chromaToMats(image)
                                val warpedU = FlowAmplifier.warp(u, maps.chromaMapX, maps.chromaMapY)
                                val warpedV = FlowAmplifier.warp(v, maps.chromaMapX, maps.chromaMapY)
                                u.release()
                                v.release()
                                try {
                                    enc.encodeFrame(ptsUs * params.slowMotionFactor) { dst ->
                                        YuvUtils.writeLuma(warpedY, dst)
                                        YuvUtils.writeChroma(warpedU, warpedV, dst)
                                    }
                                } finally {
                                    warpedY.release()
                                    warpedU.release()
                                    warpedV.release()
                                }
                            } finally {
                                maps.release()
                            }
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
