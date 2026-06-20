package tw.pp.kazi.ui.lan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.pp.kazi.data.UpdateChecker
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.theme.AppColors
import java.io.File

/**
 * 遠端遙控網頁送來「裝這顆 APK」時開的畫面(出現在電視盒上)。
 * 上傳的檔已經在 cache → 直接安裝;網址 → 先下載再安裝。最後都用系統安裝器(電視上要按確認)。
 */
@Composable
fun RemoteApkInstallScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val context = LocalContext.current
    val req by container.pendingRemoteApkInstall.collectAsState()

    var status by remember { mutableStateOf("準備中…") }
    var percent by remember { mutableStateOf(-1) }       // -1 = 沒有百分比(上傳的檔或安裝中)
    var working by remember { mutableStateOf(true) }
    var needPermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 離開畫面就清掉這筆請求(下次再送才會重觸發)
    DisposableEffect(Unit) {
        onDispose { container.consumePendingRemoteApkInstall() }
    }

    LaunchedEffect(req) {
        val r = req ?: return@LaunchedEffect
        error = null; needPermission = false; working = true; percent = -1
        if (!UpdateChecker.canInstallApks(context)) {
            working = false
            needPermission = true
            status = "要先允許本 app 安裝「未知來源 App」才能裝。按下面按鈕去開啟,開好後請在手機網頁重送一次。"
            return@LaunchedEffect
        }
        try {
            val file = if (r.localPath != null) {
                status = "已收到「${r.fileName}」,正在開啟安裝畫面…"
                File(r.localPath)
            } else {
                status = "正在下載「${r.fileName}」…"
                percent = 0
                UpdateChecker.downloadApkFromUrl(context, r.url ?: "", r.fileName) { d, t ->
                    percent = if (t > 0) ((d * 100) / t).toInt() else -1
                }
            }
            if (!file.exists() || file.length() == 0L) {
                working = false
                error = "檔案不存在或是空的,請重試"
                return@LaunchedEffect
            }
            percent = -1
            working = false
            status = "已開啟系統安裝畫面 — 請拿遙控器在電視上按「安裝」完成。"
            UpdateChecker.installApk(context, file)
        } catch (e: Exception) {
            working = false
            error = "失敗:${e.message ?: "未知錯誤"}"
        }
    }

    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }

    ScreenScaffold(
        title = "遠端安裝 APK",
        onBack = { nav.popBackStack() },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            req?.let {
                Text(it.fileName, color = AppColors.OnBg, fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
            }
            if (working) {
                CircularProgressIndicator(color = AppColors.Primary)
                Spacer(Modifier.height(14.dp))
            }
            if (percent in 0..100) {
                Text("$percent %", color = AppColors.OnBgMuted, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = error ?: status,
                color = if (error != null) AppColors.Error else AppColors.OnBgMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (needPermission) {
                AppButton(
                    text = "前往允許安裝未知來源",
                    onClick = { UpdateChecker.openInstallPermissionSettings(context) },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            AppButton(
                text = "返回",
                onClick = { nav.popBackStack() },
                primary = false,
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).focusRequester(backFocus),
            )
        }
    }
}
