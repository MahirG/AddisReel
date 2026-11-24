package et.addisreel.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun InAppUpdatePanel() {
    val context = LocalContext.current
    val activity = context as? Activity
    var busy by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<UpdateRelease?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Check for new versions without leaving AddisReel.") }
    fun ui(block: () -> Unit) { activity?.runOnUiThread(block) }
    val currentVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0" }
    val currentCode = remember {
        if (Build.VERSION.SDK_INT >= 28)
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        else context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AddisSpacing.compact)
    ) {
        AddisSectionTitle(stringResource(R.string.app_updates))
        Text("Installed version: $currentVersion • Build $currentCode",
            style = MaterialTheme.typography.bodySmall, color = AddisColors.textSecondary)
        AddisStatusMessage(status, when {
            status.contains("failed", ignoreCase = true) || status.contains("error", ignoreCase = true) ->
                AddisStatus.Error
            status.contains("available", ignoreCase = true) ||
                status.contains("complete", ignoreCase = true) ->
                AddisStatus.Success
            else -> AddisStatus.Info
        })
        if (busy && release != null) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("$progress% downloaded", style = MaterialTheme.typography.bodySmall, color = AddisColors.textSecondary)
        }
        Button(modifier = Modifier.heightIn(min = AddisSpacing.touch), enabled = !busy, onClick = {
            busy = true
            status = "Checking for updates…"
            Thread {
                try {
                    val latest = AppUpdater.check(currentVersion, currentCode)
                    ui {
                        release = latest
                        status = if (latest == null) "You're up to date. No newer published release found."
                                 else "Update ${latest.version} available. ${latest.notes}"
                    }
                } catch (e: Exception) { ui { status = "Update check failed: ${e.message}" } }
                finally { ui { busy = false } }
            }.start()
        }) { Text("Check for updates") }
        if (release != null) {
            Button(modifier = Modifier.heightIn(min = AddisSpacing.touch), enabled = !busy, onClick = {
                val existing = downloaded
                if (existing != null && existing.exists()) {
                    try { AppUpdater.install(context, existing) }
                    catch (e: Exception) { status = "Installer error: ${e.message}" }
                } else {
                    busy = true
                    status = "Downloading update securely…"
                    val target = release!!
                    Thread {
                        try {
                            val file = AppUpdater.download(context, target) { pct -> ui { progress = pct } }
                            ui {
                                downloaded = file
                                status = "Download complete. Confirm installation in Android."
                                try { AppUpdater.install(context, file) }
                                catch (e: Exception) { status = "Installer error: ${e.message}" }
                            }
                        } catch (e: Exception) { ui { status = "Download failed: ${e.message}" } }
                        finally { ui { busy = false } }
                    }.start()
                }
            }) { Text(if (downloaded != null) "Install downloaded update" else "Download and install") }
            Text("Android may ask you to allow installs from AddisReel. Updates must use the same signing certificate as your installed app.", style = MaterialTheme.typography.bodySmall, color = AddisColors.textSecondary)
        }
    }
}
