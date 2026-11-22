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
import org.json.JSONObject

data class UpdateRelease(val versionCode: Int, val version: String, val notes: String, val apkUrl: String)

object AppUpdater {
    // Only published GitHub Releases with a downloadable .apk are considered.
    private const val RELEASES = "https://api.github.com/repos/MahirG/AddisReel/releases/latest"
    fun check(currentVersion: String): UpdateRelease? {
        val connection = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AddisReel-Android")
        }
        try {
            if (connection.responseCode == 404) return null
            if (connection.responseCode !in 200..299) error("Release service unavailable (${connection.responseCode})")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            val parts = tag.split(".")
            if (parts.size != 3) return null
            val code = parts[0].toIntOrNull()?.times(10000)?.plus((parts[1].toIntOrNull() ?: return null) * 100)?.plus(parts[2].toIntOrNull() ?: return null) ?: return null
            val currentParts = currentVersion.removePrefix("v").split(".")
            val currentCode = if (currentParts.size == 3) (currentParts[0].toIntOrNull() ?: 0) * 10000 + (currentParts[1].toIntOrNull() ?: 0) * 100 + (currentParts[2].toIntOrNull() ?: 0) else Int.MAX_VALUE
            if (code <= currentCode) return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    val link = asset.getString("browser_download_url")
                    if (!link.startsWith("https://github.com/MahirG/AddisReel/releases/download/")) continue
                    return UpdateRelease(code, tag, json.optString("body").take(1000), link)
                }
            }
            return null
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
