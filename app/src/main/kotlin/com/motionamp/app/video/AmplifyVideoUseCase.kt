// 130726 Initial implementation
// 140726 Replaced EVM luma amplification with optical-flow warping (FlowAmplifier); chroma now warped too
// 150726 Rest-pose reference is now the clip's middle frame (symmetric exaggeration of oscillation)
// 150726 Pipelined: decode/extract feeds a bounded queue; flow+warp+encode run on a parallel worker
// 150726 At >=120fps, flow is computed every 2nd frame and the warp maps reused between
package com.motionamp.app.video

import com.motionamp.core.FlowAmplifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Decode raw.mp4 -> exaggerate motion per frame -> encode amplified.mp4 with
 * stretched timestamps. Two-stage pipeline: the decoder thread extracts YUV Mats
 * into a small bounded queue while a worker coroutine runs flow + warp + encode,
 * so hardware decode overlaps the CPU-heavy stages.
 */
class AmplifyVideoUseCase {

    data class Params(
        val inputPath: String,
        val outputPath: String,
        val captureFps: Int,
        val alpha: Double,
        val slowMotionFactor: Int,
    )

    private class FrameJob(val luma: Mat, val u: Mat, val v: Mat, val ptsUs: Long) {
        fun release() {
            luma.release(); u.release(); v.release()
        }
    }

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
            val encoderRef = AtomicReference<VideoEncoder?>(null)
            val workerError = AtomicReference<Throwable?>(null)
            // ~4 MB per queued 720p frame: capacity 2 bounds memory while keeping both stages busy.
            val queue = ArrayBlockingQueue<Any>(2)
            var frames = 0
            var succeeded = false

            val worker = launch {
                // At high frame rates consecutive frames barely differ: compute flow every
                // 2nd frame and reuse the warp maps between, halving the dominant cost.
                val flowStride = if (params.captureFps >= 120) 2 else 1
                var cachedMaps: FlowAmplifier.WarpMaps? = null
                try {
                    while (true) {
                        val item = runInterruptible { queue.take() }
                        if (item === END) break
                        val job = item as FrameJob
                        try {
                            val enc = encoderRef.get() ?: VideoEncoder(
                                width = job.luma.cols(),
                                height = job.luma.rows(),
                                frameRate = max(1, params.captureFps / params.slowMotionFactor),
                                bitRate = bitRate,
                                outputPath = params.outputPath,
                                orientationDegrees = info.rotationDegrees,
                            ).also { encoderRef.set(it) }
                            if (frames % flowStride == 0 || cachedMaps == null) {
                                cachedMaps?.release()
                                cachedMaps = amplifier.computeMaps(job.luma)
                            }
                            val maps = cachedMaps
                            if (maps == null) {
                                // Rest-pose reference frame: pass through unchanged.
                                enc.encodeFrame(job.ptsUs * params.slowMotionFactor) { dst ->
                                    YuvUtils.writeLuma(job.luma, dst)
                                    YuvUtils.writeChroma(job.u, job.v, dst)
                                }
                            } else {
                                val warpedY =
                                    FlowAmplifier.warp(job.luma, maps.lumaMapX, maps.lumaMapY)
                                val warpedU =
                                    FlowAmplifier.warp(job.u, maps.chromaMapX, maps.chromaMapY)
                                val warpedV =
                                    FlowAmplifier.warp(job.v, maps.chromaMapX, maps.chromaMapY)
                                try {
                                    enc.encodeFrame(job.ptsUs * params.slowMotionFactor) { dst ->
                                        YuvUtils.writeLuma(warpedY, dst)
                                        YuvUtils.writeChroma(warpedU, warpedV, dst)
                                    }
                                } finally {
                                    warpedY.release(); warpedU.release(); warpedV.release()
                                }
                            }
                            frames++
                            onProgress(min(0.99f, frames.toFloat() / totalFrames))
                        } finally {
                            job.release()
                        }
                    }
                } catch (t: Throwable) {
                    // Record the failure and unblock the producer; the main path rethrows it.
                    workerError.compareAndSet(null, t)
                    drainQueue(queue)
                    if (t is CancellationException) throw t
                } finally {
                    cachedMaps?.release()
                }
            }

            try {
                decoder.decode { image, ptsUs ->
                    if (!isActive || workerError.get() != null) return@decode false
                    val luma = YuvUtils.lumaToMat(image)
                    val (u, v) = YuvUtils.chromaToMats(image)
                    val job = FrameJob(luma, u, v, ptsUs)
                    // Bounded offer with liveness checks so a dead worker can't deadlock us.
                    while (!queue.offer(job, 100, TimeUnit.MILLISECONDS)) {
                        if (!isActive || workerError.get() != null) {
                            job.release()
                            return@decode false
                        }
                    }
                    true
                }
                // Signal end-of-stream; keep checking liveness in case the worker died full-queue.
                while (!queue.offer(END, 100, TimeUnit.MILLISECONDS)) {
                    if (!isActive || workerError.get() != null) break
                }
                worker.join()
                workerError.get()?.let { throw it }
                ensureActive() // cancelled mid-decode: fall through to catch, not success
                val enc = encoderRef.get() ?: error("no frames decoded from ${params.inputPath}")
                encoderRef.set(null) // finish() tears the encoder down even on failure; never call it twice
                enc.finish()
                succeeded = true
                onProgress(1f)
            } catch (t: Throwable) {
                worker.cancel()
                drainQueue(queue)
                runCatching { encoderRef.getAndSet(null)?.finish() }
                if (!succeeded) File(params.outputPath).delete()
                throw t
            } finally {
                amplifier.release()
            }
        }

    private fun drainQueue(queue: ArrayBlockingQueue<Any>) {
        while (true) {
            val left = queue.poll() ?: break
            (left as? FrameJob)?.release()
        }
    }

    private companion object {
        val END = Any()
    }
}
