// 130726 Initial implementation
package com.motionamp.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class MotionAmpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            // Processing cannot work without the native lib; surface loudly in logs.
            Log.e("MotionAmp", "OpenCV native library failed to load")
        }
    }
}
