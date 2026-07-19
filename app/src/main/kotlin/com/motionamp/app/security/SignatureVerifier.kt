// 190726 Initial implementation
package com.motionamp.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Repackaging detection: compares the SHA-256 of the installed APK's signing
 * certificate against the release certificate pinned at build time. A purely
 * client-side check — a determined attacker can patch it out of the APK — so
 * it raises the bar against casual re-signing rather than being a hard wall.
 */
object SignatureVerifier {

    // SHA-256 of the release signing certificate (motionamp-release.jks).
    private const val RELEASE_CERT_SHA256 =
        "dc84ae4fb6930630211d6de347dc125209c7530366a9399d42f0222f242f691f"

    /** True iff every signer of the installed APK matches the pinned release cert. */
    fun isGenuine(context: Context): Boolean {
        val certs = signingCertificates(context)
        return certs.isNotEmpty() && certs.all { sha256Hex(it) == RELEASE_CERT_SHA256 }
    }

    private fun signingCertificates(context: Context): List<ByteArray> = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray() }.orEmpty()
        }
    } catch (_: Exception) {
        emptyList() // fail closed: an unreadable signature is treated as not genuine
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
