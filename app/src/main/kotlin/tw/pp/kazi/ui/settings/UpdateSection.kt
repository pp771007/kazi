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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    data class Downloading(val downloaded: Long, val total: Long) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val windowSize = tw.pp.kazi.ui.LocalWindowSize.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    // 沒授權時暫存使用者點的 asset，等他從系統設定回來後 ON_RESUME 自動開始下載
    var pendingAsset by remember { mutableStateOf<GitHubAsset?>(null) }
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

    fun startDownload(asset: GitHubAsset) {
        state = UpdateUiState.Downloading(0L, asset.size.coerceAtLeast(1L))
        scope.launch {
            runCatching {
                UpdateChecker.download(context, asset) { down, total ->
                    state = UpdateUiState.Downloading(down, total.coerceAtLeast(1L))
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

    fun startDownloadAndInstall(asset: GitHubAsset) {
        // 先檢查權限再下載，免得使用者下載完才被擋下、要再下一次
        if (!UpdateChecker.canInstallApks(context)) {
            pendingAsset = asset
            showPermissionDialog = true
            return
        }
        startDownload(asset)
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
                    pendingAsset = null
                    currentDownload.value(asset)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // state 變動後 focus 會掉（按鈕 composable 被換掉，TV 上 focus 預設會跳走），
    // 用 FocusRequester 抓回來，讓「檢查更新 → 檢查中 → 下載並安裝」連續按下去 focus 不會跳出 UpdateSection。
    // 跳過初次 composition 那一發（state=Idle）— 不然進設定頁就會搶走 focus，蓋過外層想 focus 「站點管理」的需求。
    // 只在 TV 跑：手機觸控不需要 keep focus within section
    val mainButtonFocus = remember { FocusRequester() }
    var skipFirstFocus by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        if (skipFirstFocus) {
            skipFirstFocus = false
            return@LaunchedEffect
        }
        if (!windowSize.isTv) return@LaunchedEffect
        kotlinx.coroutines.delay(50)  // 等新按鈕 attach 到 layout
        runCatching { mainButtonFocus.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            UpdateUiState.Idle -> AppButton(
                text = "檢查更新",
                icon = Icons.Filled.SystemUpdate,
                onClick = ::startCheck,
                primary = false,
                modifier = Modifier.focusRequester(mainButtonFocus),
            )

            UpdateUiState.Checking -> AppButton(
                text = "檢查中⋯",
                onClick = {},
                enabled = false,
                primary = false,
                modifier = Modifier.focusRequester(mainButtonFocus),
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
                    modifier = Modifier.focusRequester(mainButtonFocus),
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
                    modifier = Modifier.focusRequester(mainButtonFocus),
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
                    modifier = Modifier.focusRequester(mainButtonFocus),
                )
            }
        }
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
