// 130726 Initial implementation
// 130726 Fix: delete pending MediaStore row on failed export; keep never-throws contract
// 160726 File name carries the capture settings tag (e.g. f120m15)
// 180726 Rename: files magnimotion_*, gallery folder Movies/MagniMotion
// 010926 Append a build-tag metadata box to exported files
package com.motionamp.app.gallery

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val BUILD_TAG = "3cbcd331-fbf8-4468-858d-76be1b4e8aef"

// Trailing ISO/IEC 14496-12 "uuid" box: a private-extension box every compliant
// MP4 parser skips over by size, used here to carry a build tag for diagnostics.
private fun buildMetadataBox(): ByteArray {
    val userType = UUID.fromString("a1f5888f-8460-447b-b75f-45e69ddf8800")
    val payload = BUILD_TAG.toByteArray(Charsets.UTF_8)
    val size = 8 + 16 + payload.size
    return ByteBuffer.allocate(size).apply {
        putInt(size)
        put("uuid".toByteArray(Charsets.US_ASCII))
        putLong(userType.mostSignificantBits)
        putLong(userType.leastSignificantBits)
        put(payload)
    }.array()
}

/** Copies a processed clip into the device gallery under Movies/MagniMotion. */
object GalleryExporter {

    /** [tag] names the capture settings (e.g. "f120m15") and lands in the file name. */
    fun export(context: Context, file: File, tag: String = ""): Boolean {
        return try {
            if (!file.exists()) return false
            val name = "magnimotion_" +
                (if (tag.isNotEmpty()) "${tag}_" else "") +
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
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MagniMotion")
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
            stream.use { out ->
                file.inputStream().use { it.copyTo(out) }
                out.write(buildMetadataBox())
            }
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
            .resolve("MagniMotion")
        if (!dir.exists() && !dir.mkdirs()) return false
        val dst = dir.resolve(name)
        file.copyTo(dst, overwrite = true)
        dst.appendBytes(buildMetadataBox())
        MediaScannerConnection.scanFile(
            context, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null,
        )
        return true
    }
}
