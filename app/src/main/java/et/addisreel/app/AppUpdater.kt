package et.addisreel.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class UpdateRelease(val versionCode: Int, val version: String, val notes: String, val apkUrl: String, val sha256: String)

object AppUpdater {
    private const val RELEASES = "https://addisreel-updates.eddieaman475.workers.dev/v1/latest"
    fun check(currentVersion: String): UpdateRelease? {
        val connection = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AddisReel-Android")
        }
        try {
            if (connection.responseCode == 404) return null
            if (connection.responseCode !in 200..299) error("Release service unavailable (${connection.responseCode})")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("version")
            val code = json.getInt("versionCode")
            val parts = tag.split(".").mapNotNull { it.toIntOrNull() }
            val installed = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size != 3 || installed.size != 3) return null
            if (parts.zip(installed).firstOrNull { it.first != it.second }?.let { it.first < it.second } == true || parts == installed) return null
            val sha = json.getString("sha256").lowercase()
            val link = json.getString("apkUrl")
            if (!sha.matches(Regex("[a-f0-9]{64}")) || !link.startsWith("https://addisreel-updates.eddieaman475.workers.dev/v1/apk/")) return null
            return UpdateRelease(code, tag, json.optString("notes"), link, sha)
        } finally { connection.disconnect() }
    }

    fun download(context: Context, release: UpdateRelease, progress: (Int) -> Unit): File {
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        val file = File(context.cacheDir, "updates/addisreel-update.apk")
        file.parentFile?.mkdirs()
        try {
            if (connection.responseCode !in 200..299) error("Download failed (${connection.responseCode})")
            val length = connection.contentLengthLong
            var received = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        if (length > 0) progress((received * 100 / length).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (file.length() < 1024) error("Downloaded APK is invalid")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(32768)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    digest.update(bytes, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(release.sha256, ignoreCase = true)) error("APK integrity check failed")
            progress(100)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally { connection.disconnect() }
    }

    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
