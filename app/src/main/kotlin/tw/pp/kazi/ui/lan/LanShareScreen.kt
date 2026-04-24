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
            title = "LAN 設定分享",
            subtitle = "掃 QR 或用網址，手機／電腦協助",
            trailing = {
                AppButton(
                    text = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { nav.popBackStack() },
                    primary = false,
                    iconOnly = compact,
                )
            },
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
            Row(
                modifier = Modifier.fillMaxSize().padding(windowSize.pagePadding()),
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
            Text(
                url ?: "",
                color = AppColors.OnBg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
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
                "尚未啟用 LAN 分享",
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        AppButton(
            text = if (running) "停止分享" else "啟用 LAN 分享",
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
        Step("2", "啟用 LAN 分享", "點左邊「啟用」按鈕")
        Step("3", "掃 QR 或輸入網址", "手機瀏覽器開啟，有「遠端搜尋」與「站點管理」兩個頁")
        Step("4", "用完關閉", "避免同 WiFi 的其他人連入")
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x22FFFFFF))
                .padding(12.dp),
        ) {
            Text(
                "⚠ 此模式無密碼保護。僅建議在自家 WiFi 使用。",
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
