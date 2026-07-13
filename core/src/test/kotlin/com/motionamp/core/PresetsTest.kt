// 130726 Initial implementation
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
    fun evmConstantsMatchSpec() {
        assertEquals(0.4, EvmConstants.LOW_CUTOFF_HZ, 0.0)
        assertEquals(8.0, EvmConstants.HIGH_CUTOFF_HZ, 0.0)
        assertEquals(4, EvmConstants.PYRAMID_LEVELS)
        assertEquals(10_000, EvmConstants.MAX_RECORDING_MS)
    }
}
