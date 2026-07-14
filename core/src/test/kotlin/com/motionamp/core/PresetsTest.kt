// 130726 Initial implementation
// 140726 Added matchingCapture mapping test
package com.motionamp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetsTest {
    @Test
    fun amplificationAlphasMatchSpec() {
        assertEquals(5.0, AmplificationPreset.LOW.alpha, 0.0)
        assertEquals(15.0, AmplificationPreset.MEDIUM.alpha, 0.0)
        assertEquals(30.0, AmplificationPreset.HIGH.alpha, 0.0)
    }

    @Test
    fun slowMotionFactorsMatchSpec() {
        assertEquals(listOf(1, 2, 4, 8), SlowMotionPreset.entries.map { it.factor })
    }

    @Test
    fun frameRateOptionsMatchSpec() {
        assertEquals(listOf(30, 60, 120, 240), FRAME_RATE_OPTIONS)
    }

    @Test
    fun totalFactorStacksPresetOnFrameRateNormalisation() {
        // 1x preset: high-fps capture alone slows playback to 30 fps effective speed.
        assertEquals(1, SlowMotionPreset.X1.totalFactorFor(30))
        assertEquals(2, SlowMotionPreset.X1.totalFactorFor(60))
        assertEquals(4, SlowMotionPreset.X1.totalFactorFor(120))
        assertEquals(8, SlowMotionPreset.X1.totalFactorFor(240))
        // Preset multiplies the normalised rate: 120 fps + half-speed = 8x slower.
        assertEquals(8, SlowMotionPreset.X2.totalFactorFor(120))
        assertEquals(64, SlowMotionPreset.X8.totalFactorFor(240))
        assertEquals(2, SlowMotionPreset.X2.totalFactorFor(30))
    }

    @Test
    fun captureConstantsMatchSpec() {
        assertEquals(10_000, CaptureConstants.MAX_RECORDING_MS)
    }
}
