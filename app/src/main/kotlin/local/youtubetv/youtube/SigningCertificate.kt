package local.youtubetv.youtube

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest
import local.youtubetv.LOG_TAG

/**
 * The SHA-1 of the certificate this build was signed with, as uppercase hex with no colons.
 *
 * Google enforces an API key's Android application restriction from the `X-Android-Package`
 * and `X-Android-Cert` request headers, not by inspecting the APK. A plain HTTP client sends
 * neither on its own, so the app has to read its own certificate and send it. See ticket 14
 * and [HttpYouTubeApi].
 *
 * Returns null if anything goes wrong. A null only means the two headers are left off, which
 * is exactly the state the app is in today, while the key carries no application restriction.
 */
fun signingCertificateSha1(context: Context): String? = try {
    val packageManager = context.packageManager
    val packageName = context.packageName
    val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        val info = packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
    }
    signatures?.firstOrNull()?.toByteArray()?.let { bytes ->
        MessageDigest.getInstance("SHA-1").digest(bytes).toUppercaseHex()
    }
} catch (failure: PackageManager.NameNotFoundException) {
    Log.w(LOG_TAG, "Could not read this app's own signing certificate", failure)
    null
}

private fun ByteArray.toUppercaseHex(): String =
    joinToString("") { byte -> "%02X".format(byte) }
