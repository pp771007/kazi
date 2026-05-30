package tw.pp.kazi.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.async

/**
 * 進歷史 / 收藏頁時主動拉一次同步(把別台最新的進度 / 收藏抓下來;平常只有冷啟動 / 回前景才拉)。
 *
 * 同步本體跑在 appScope.async → 即使使用者馬上離開這頁也會跑完,不會半途被取消;
 * await 只是讓「這次成功與否」回到這個 LaunchedEffect 好決定要不要提示(離開頁面時 await 被取消、
 * 不影響 appScope 那條已經在跑的同步)。
 *
 * 只有「已設定同步(syncEnabled)而這次失敗」才跳 toast → 成功安靜不打擾、沒設定同步的人也不會被無謂提示。
 */
@Composable
fun SyncOnEnter() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val ok = container.appScope.async { container.syncManager.sync() }.await()
        if (!ok && container.configRepository.settings.value.syncEnabled) {
            Toast.makeText(context, "同步失敗,請檢查網路或同步設定", Toast.LENGTH_SHORT).show()
        }
    }
}
