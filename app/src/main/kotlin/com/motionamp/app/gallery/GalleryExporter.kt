// 130726 Initial implementation
// 130726 Fix: delete pending MediaStore row on failed export; keep never-throws contract
package com.motionamp.app.gallery

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copies a processed clip into the device gallery under Movies/MotionAmp. */
object GalleryExporter {

    fun export(context: Context, file: File): Boolean {
        return try {
            if (!file.exists()) return false
            val name = "motionamp_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            if (Build.VERSION.SDK_INT >= 29) exportViaMediaStore(context, file, name)
            else exportLegacy(context, file, name)
        } catch (e: Exception) {
            Log.e("MotionAmp", "gallery export failed", e)
            false
        }
    }

    private fun exportViaMediaStore(context: Context, file: File, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotionAmp")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.delete(uri, null, null)
                return false
            }
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        } catch (e: Exception) {
            // Don't strand an invisible pending row on a failed copy.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** API 26-28: direct copy to the public Movies dir + media scan. */
    private fun exportLegacy(context: Context, file: File, name: String): Boolean {
        val dir = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            .resolve("MotionAmp")
        if (!dir.exists() && !dir.mkdirs()) return false
        val dst = dir.resolve(name)
        file.copyTo(dst, overwrite = true)
        MediaScannerConnection.scanFile(
            context, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null,
        )
        return true
    }
}
