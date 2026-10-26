package dev.mrwick.redline.util

import android.content.Context
import dev.mrwick.redline.BuildConfig
import java.io.File
import java.security.MessageDigest

internal object BuildIdentity {
    val summary: String
        get() = "version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
            "package=${BuildConfig.APPLICATION_ID} source=${BuildConfig.SOURCE_FINGERPRINT}"

    fun installedApkSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun capture(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "BuildIdentity=$summary\n" +
            "FirstInstallMs=${info.firstInstallTime}\nLastUpdateMs=${info.lastUpdateTime}\n" +
            "InstalledBaseApkSha256=${installedApkSha256(File(context.applicationInfo.sourceDir))}"
    }
}
