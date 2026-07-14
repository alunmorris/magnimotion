// 130726 Initial implementation
// 140726 Slow motion now stacks on frame-rate normalisation (totalFactorFor): 120fps+½× = 8× slower
package com.motionamp.core

/** Amplification gain applied to the band-passed motion signal. */
enum class AmplificationPreset(val alpha: Double, val label: String) {
    LOW(5.0, "×5"),
    MEDIUM(15.0, "×15"),
    HIGH(30.0, "×30"),
}

/** Additional playback slow-down applied on top of the frame-rate normalisation. */
enum class SlowMotionPreset(val factor: Int, val label: String) {
    X1(1, "1×"),
    X2(2, "½×"),
    X4(4, "¼×"),
    X8(8, "⅛×"),
    ;

    /**
     * Total encode-timestamp stretch for a clip captured at [captureFps]: capture is
     * first normalised to 30 fps playback (120 fps clip → 4× slower), then this preset
     * multiplies that (½× on a 120 fps clip → 8× slower than real time).
     */
    fun totalFactorFor(captureFps: Int): Int = maxOf(1, captureFps / 30) * factor
}

/** Capture frame rates offered in the UI; availability is device-dependent. */
val FRAME_RATE_OPTIONS: List<Int> = listOf(30, 60, 120, 240)

object EvmConstants {
    /** Temporal band amplified, in Hz — broad general-purpose band per spec. */
    const val LOW_CUTOFF_HZ = 0.4
    const val HIGH_CUTOFF_HZ = 8.0

    /** Gaussian pyramid depth; the finest full-res level is never amplified. */
    const val PYRAMID_LEVELS = 4

    const val MAX_RECORDING_MS = 10_000
}
