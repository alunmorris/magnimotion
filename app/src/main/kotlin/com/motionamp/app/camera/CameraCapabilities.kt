// 130726 Initial implementation
package com.motionamp.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size

data class CameraCaps(
    val cameraId: String,
    val normalRates: List<Int>,          // subset of {30, 60}
    val highSpeedRates: Map<Int, Size>,  // 120/240 -> recording size
) {
    val supportedRates: List<Int> get() = (normalRates + highSpeedRates.keys).sorted()
}

object CameraCapabilities {

    /** Query the back camera's frame-rate support. Throws if no camera; caller
     *  falls back to CameraCaps(id, [30], empty) on any failure. */
    fun query(context: Context): CameraCaps {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.first()
        val chars = mgr.getCameraCharacteristics(id)

        val aeRanges =
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: emptyArray()
        // Fixed-rate ranges only: a (30,30) range guarantees steady sampling for EVM.
        val normal = listOf(30, 60).filter { r ->
            aeRanges.any { it.lower == r && it.upper == r }
        }.ifEmpty { listOf(30) }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val high = mutableMapOf<Int, Size>()
        map?.highSpeedVideoFpsRanges?.forEach { range ->
            if (range.lower == range.upper && (range.upper == 120 || range.upper == 240)) {
                val sizes = map.getHighSpeedVideoSizesFor(range)
                val best = sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }
                if (best != null) high[range.upper] = best
            }
        }
        return CameraCaps(id, normal, high)
    }
}
