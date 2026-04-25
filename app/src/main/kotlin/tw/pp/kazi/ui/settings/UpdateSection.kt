package tw.pp.kazi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import tw.pp.kazi.data.GitHubAsset
import tw.pp.kazi.data.GitHubRelease
import tw.pp.kazi.data.UpdateChecker
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.io.File

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class HasUpdate(val release: GitHubRelease, val asset: GitHubAsset) : UpdateUiState
    data class Downloading(val downloaded: Long, val total: Long) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var pendingApk by remember { mutableStateOf<File?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun startCheck() {
        state = UpdateUiState.Checking
        scope.launch {
            runCatching { UpdateChecker.fetchLatest() }.fold(
                onSuccess = { release ->
                    state = if (UpdateChecker.isNewerThanLocal(release.tagName)) {
                        val asset = UpdateChecker.pickApkAsset(release)
                        if (asset != null) {
                            UpdateUiState.HasUpdate(release, asset)
                        } else {
                            UpdateUiState.Error("最新版沒有 APK 檔可下載")
                        }
                    } else {
                        UpdateUiState.UpToDate(release.tagName.removePrefix("v"))
                    }
                },
                onFailure = { state = UpdateUiState.Error(it.message ?: "檢查失敗") },
            )
        }
    }

    fun launchInstaller(file: File) {
        if (UpdateChecker.canInstallApks(context)) {
            UpdateChecker.installApk(context, file)
        } else {
            showPermissionDialog = true
        }
    }

    fun startDownloadAndInstall(asset: GitHubAsset) {
        state = UpdateUiState.Downloading(0L, asset.size.coerceAtLeast(1L))
        scope.launch {
            runCatching {
                UpdateChecker.download(context, asset) { down, total ->
                    state = UpdateUiState.Downloading(down, total.coerceAtLeast(1L))
                }
            }.fold(
                onSuccess = { file ->
                    pendingApk = file
                    launchInstaller(file)
                    // 留在 Idle，使用者可重新檢查 / 再裝一次（系統安裝對話框可能被關掉）
                    state = UpdateUiState.Idle
                },
                onFailure = { state = UpdateUiState.Error(it.message ?: "下載失敗") },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            UpdateUiState.Idle -> AppButton(
                text = "檢查更新",
                icon = Icons.Filled.SystemUpdate,
                onClick = ::startCheck,
                primary = false,
            )

            UpdateUiState.Checking -> AppButton(
                text = "檢查中⋯",
                onClick = {},
                enabled = false,
                primary = false,
            )

            is UpdateUiState.UpToDate -> {
                Text(
                    "✓ 已是最新版（v${s.version}）",
                    color = AppColors.Success,
                    style = MaterialTheme.typography.bodySmall,
                )
                AppButton(
                    text = "重新檢查",
                    icon = Icons.Filled.Refresh,
                    onClick = ::startCheck,
                    primary = false,
                )
            }

            is UpdateUiState.HasUpdate -> {
                Text(
                    "有新版 ${s.release.tagName}",
                    color = AppColors.Accent,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                s.release.body?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(
                        notes.lines().filter { it.isNotBlank() }.take(5).joinToString("\n"),
                        color = AppColors.OnBgMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AppButton(
                    text = "下載並安裝",
                    icon = Icons.Filled.Download,
                    onClick = { startDownloadAndInstall(s.asset) },
                )
            }

            is UpdateUiState.Downloading -> {
                val pct = (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f)
                Text(
                    "下載中 ${(pct * 100).toInt()}%  (${formatBytes(s.downloaded)} / ${formatBytes(s.total)})",
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                ProgressBar(progress = pct)
            }

            is UpdateUiState.Error -> {
                Text(
                    "⚠ ${s.message}",
                    color = AppColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                )
                AppButton(
                    text = "重試",
                    icon = Icons.Filled.Refresh,
                    onClick = ::startCheck,
                    primary = false,
                )
            }
        }
    }

    if (showPermissionDialog) {
        InstallPermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = {
                UpdateChecker.openInstallPermissionSettings(context)
                showPermissionDialog = false
            },
        )
    }

    // 從系統設定授權回來後，自動把剛剛下好的 APK 再丟給 installer 一次，免得使用者要手動重來
    LaunchedEffect(showPermissionDialog) {
        if (!showPermissionDialog && pendingApk != null && UpdateChecker.canInstallApks(context)) {
            pendingApk?.let { UpdateChecker.installApk(context, it) }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(AppColors.BgElevated),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(AppColors.Primary),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InstallPermissionDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.BgCard)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "需要安裝權限",
                color = AppColors.OnBg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Android 8.0 起每個 app 都要單獨授權「允許安裝其他 app」。" +
                    "點下面的按鈕會跳到系統設定，把咔滋影院切到允許後返回，會自動繼續安裝。",
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(2.dp))
            AppButton(
                text = "開啟系統設定",
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = onOpenSettings,
            )
            AppButton(
                text = "取消",
                onClick = onDismiss,
                primary = false,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "$bytes ${units[0]}" else "%.1f %s".format(v, units[i])
}
