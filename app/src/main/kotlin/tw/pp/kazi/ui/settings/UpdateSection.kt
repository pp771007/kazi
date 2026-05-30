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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import tw.pp.kazi.data.GitHubAsset
import tw.pp.kazi.data.GitHubRelease
import tw.pp.kazi.data.UpdateChecker
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val version: String) : UpdateUiState
    data class HasUpdate(val release: GitHubRelease, val asset: GitHubAsset) : UpdateUiState
    data class Downloading(val downloaded: Long, val total: Long, val version: String) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

private data class ButtonSpec(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val action: () -> Unit,
    val enabled: Boolean,
    val primary: Boolean,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    // 沒授權時暫存使用者點的 asset + version，等他從系統設定回來後 ON_RESUME 自動開始下載
    var pendingAsset by remember { mutableStateOf<GitHubAsset?>(null) }
    var pendingVersion by remember { mutableStateOf("") }
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun startDownload(asset: GitHubAsset, version: String) {
        state = UpdateUiState.Downloading(0L, asset.size.coerceAtLeast(1L), version)
        scope.launch {
            runCatching {
                UpdateChecker.download(context, asset) { down, total ->
                    state = UpdateUiState.Downloading(down, total.coerceAtLeast(1L), version)
                }
            }.fold(
                onSuccess = { file ->
                    UpdateChecker.installApk(context, file)
                    state = UpdateUiState.Idle
                },
                onFailure = { state = UpdateUiState.Error(it.message ?: "下載失敗") },
            )
        }
    }

    fun startDownloadAndInstall(asset: GitHubAsset, version: String) {
        // 先檢查權限再下載，免得使用者下載完才被擋下、要再下一次
        if (!UpdateChecker.canInstallApks(context)) {
            pendingAsset = asset
            pendingVersion = version
            showPermissionDialog = true
            return
        }
        startDownload(asset, version)
    }

    fun startCheck() {
        state = UpdateUiState.Checking
        scope.launch {
            runCatching { UpdateChecker.fetchLatest() }.fold(
                onSuccess = { release ->
                    if (UpdateChecker.isNewerThanLocal(release.tagName)) {
                        val asset = UpdateChecker.pickApkAsset(release)
                        if (asset != null) {
                            // 偵測到新版直接觸發下載＋安裝，省掉「下載並安裝」那一步點擊。
                            // 沒授權時 startDownloadAndInstall 會跳權限 dialog，HasUpdate
                            // 畫面同時顯示版本資訊作為背景，dialog 關掉時也還能看到資訊
                            state = UpdateUiState.HasUpdate(release, asset)
                            startDownloadAndInstall(asset, release.tagName)
                        } else {
                            state = UpdateUiState.Error("最新版沒有 APK 檔可下載")
                        }
                    } else {
                        state = UpdateUiState.UpToDate(release.tagName.removePrefix("v"))
                    }
                },
                onFailure = { state = UpdateUiState.Error(it.message ?: "檢查失敗") },
            )
        }
    }

    // 使用者去系統設定打開「允許安裝其他 app」回來時，ON_RESUME 觸發；
    // 如果剛才有被卡住的下載，現在自動開始
    val currentDownload = rememberUpdatedState(::startDownload)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val asset = pendingAsset
                if (asset != null && UpdateChecker.canInstallApks(context)) {
                    val version = pendingVersion
                    pendingAsset = null
                    pendingVersion = ""
                    currentDownload.value(asset, version)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 按鈕 props 隨 state 改,但永遠是「同一個 call site、永遠 render、永遠可聚焦」:
    // Checking / Downloading 這種忙碌狀態以 enabled=false 變灰 + keepFocusable=true 留在原地,
    // 焦點就不會因為按鈕 disable / 消失被迫跳去站點管理。因此也不再需要先前那段「state 變動後
    // 用 FocusRequester 把焦點硬抓回來」的 OK 繃(已移除)。
    val s = state
    val buttonSpec: ButtonSpec = when (s) {
        UpdateUiState.Idle -> ButtonSpec("更新程式", Icons.Filled.SystemUpdate, ::startCheck, true, false)
        UpdateUiState.Checking -> ButtonSpec("檢查中⋯", null, {}, false, false)
        is UpdateUiState.UpToDate -> ButtonSpec("重新檢查", Icons.Filled.Refresh, ::startCheck, true, false)
        is UpdateUiState.HasUpdate -> ButtonSpec("下載並安裝", Icons.Filled.Download, { startDownloadAndInstall(s.asset, s.release.tagName) }, true, true)
        is UpdateUiState.Downloading -> ButtonSpec("下載中⋯", null, {}, false, false)
        is UpdateUiState.Error -> ButtonSpec("重試", Icons.Filled.Refresh, ::startCheck, true, false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 上方狀態文字 / 進度條（依 state 不同；按鈕在下方統一渲染）
        when (s) {
            is UpdateUiState.UpToDate -> Text(
                "✓ 沒有新版本（目前 v${s.version}）",
                color = AppColors.Success,
                style = MaterialTheme.typography.bodySmall,
            )
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
            }
            is UpdateUiState.Downloading -> {
                val pct = (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f)
                Text(
                    "下載中 ${s.version}  ·  ${(pct * 100).toInt()}%  (${formatBytes(s.downloaded)} / ${formatBytes(s.total)})",
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                ProgressBar(progress = pct)
            }
            is UpdateUiState.Error -> Text(
                "⚠ ${s.message}",
                color = AppColors.Error,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Unit
        }

        // 主按鈕:永遠是同一個 AppButton call site、永遠 render。忙碌狀態 keepFocusable 讓焦點留在原地。
        AppButton(
            text = buttonSpec.label,
            icon = buttonSpec.icon,
            onClick = buttonSpec.action,
            enabled = buttonSpec.enabled,
            keepFocusable = true,
            primary = buttonSpec.primary,
        )
    }

    if (showPermissionDialog) {
        InstallPermissionDialog(
            onDismiss = {
                // 使用者按取消 = 不繼續，把 pending 清掉避免之後 ON_RESUME 誤觸發下載
                showPermissionDialog = false
                pendingAsset = null
            },
            onOpenSettings = {
                UpdateChecker.openInstallPermissionSettings(context)
                showPermissionDialog = false
                // pendingAsset 留著，回來時 ON_RESUME 觀察者會自動續接下載
            },
        )
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
