package tw.pp.kazi.ui.lan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.GradientTopBar
import tw.pp.kazi.ui.components.SectionHeader
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.sectionGap
import tw.pp.kazi.ui.theme.AppColors
import tw.pp.kazi.util.QrCode
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LanShareScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val compact = windowSize.isCompact
    val state by container.lanState.collectAsState()
    val scope = rememberCoroutineScope()

    val qrBitmap: ImageBitmap? = remember(state.url) {
        state.url?.let { runCatching { QrCode.encode(it, QR_PX) }.getOrNull() }
    }

    val qrPanel: @Composable () -> Unit = {
        QrPanel(
            running = state.running,
            url = state.url,
            qrBitmap = qrBitmap,
            onToggle = {
                scope.launch {
                    if (state.running) {
                        container.stopLan()
                        container.configRepository.updateLanShare(false)
                    } else {
                        if (container.startLan()) {
                            container.configRepository.updateLanShare(true)
                        }
                    }
                }
            },
            compact = compact,
        )
    }
    val stepsPanel: @Composable () -> Unit = { StepsPanel(compact = compact) }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientTopBar(
            title = "遠端遙控",
            subtitle = "掃 QR 或用網址，手機／電腦協助",
            onBack = { nav.popBackStack() },
        )

        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = windowSize.pagePadding(), vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(windowSize.sectionGap()),
            ) {
                qrPanel()
                stepsPanel()
            }
        } else {
            // Phone landscape (Medium) 高度只有 ~360dp，QR + 按鈕加起來會超過螢幕高度，
            // 沒有 verticalScroll 會被切掉看不到啟用按鈕
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(windowSize.pagePadding()),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(modifier = Modifier.width(QR_COL_WIDTH)) { qrPanel() }
                Box(modifier = Modifier.weight(1f)) { stepsPanel() }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QrPanel(
    running: Boolean,
    url: String?,
    qrBitmap: ImageBitmap?,
    onToggle: () -> Unit,
    compact: Boolean,
) {
    val qrSize = if (compact) QR_DISPLAY_COMPACT else QR_DISPLAY_WIDE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.BgCard)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (running && qrBitmap != null) {
            Box(
                modifier = Modifier
                    .size(qrSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "QR Code",
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    url ?: "",
                    color = AppColors.OnBg,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                url?.let { u ->
                    val clipboard = LocalClipboardManager.current
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(copied) {
                        if (copied) {
                            kotlinx.coroutines.delay(1500)
                            copied = false
                        }
                    }
                    AppButton(
                        text = if (copied) "已複製" else "複製",
                        icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        onClick = {
                            clipboard.setText(AnnotatedString(u))
                            copied = true
                        },
                        primary = false,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(qrSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D0D15)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.tv.material3.Icon(
                    Icons.Filled.QrCode2, null,
                    tint = AppColors.OnBgDim,
                    modifier = Modifier.size(80.dp),
                )
            }
            Text(
                "尚未啟用遠端遙控",
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AppButton(
            text = if (running) "停止遠端遙控" else "啟用遠端遙控",
            icon = if (running) Icons.Filled.StopCircle else Icons.Filled.PlayCircle,
            onClick = onToggle,
            danger = running,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StepsPanel(compact: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.BgCard)
            .padding(if (compact) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(title = "使用說明")
        Step("1", "同一個 WiFi", "此裝置與手機需連同一網路")
        Step("2", "啟用遠端遙控", "點左邊「啟用」按鈕（首頁的 QR icon 會自動順手開）")
        Step("3", "掃 QR 或輸入網址", "手機瀏覽器開啟即可使用三個頁籤")
        Spacer(Modifier.height(2.dp))
        Step("📱", "遠端搜尋", "手機打字、勾站點，送出 → TV 跳到搜尋結果。歷史可清空。")
        Step("📥", "匯入站點", "從手機 app 站點管理「📋 匯出站點」複製 → 在這頁貼上 → 預覽 → 匯入")
        Step("⚙️", "站點管理", "新增 / 啟停 / 排序 / 刪除站台，跟手機 app 即時同步")
        Spacer(Modifier.height(4.dp))
        Step("4", "用完關閉", "點首頁標題旁的「🟢 遠端遙控」膠囊一下就關，或回這頁按「停止」")
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF))
                .padding(12.dp),
        ) {
            Text(
                "⚠ 此模式無密碼保護，同 WiFi 的人只要拿到網址都能進來操控。僅建議在自家 WiFi 使用。",
                color = AppColors.Warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Step(num: String, title: String, desc: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.Primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                num, color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title, color = AppColors.OnBg, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(desc, color = AppColors.OnBgMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val QR_COL_WIDTH = 460.dp
private val QR_DISPLAY_COMPACT = 200.dp
private val QR_DISPLAY_WIDE = 300.dp
private const val QR_PX = 680
