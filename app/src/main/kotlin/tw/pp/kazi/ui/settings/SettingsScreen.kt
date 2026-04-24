package tw.pp.kazi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tw.pp.kazi.data.ViewMode
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.GradientTopBar
import tw.pp.kazi.ui.components.SectionHeader
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.sectionGap
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val settings by container.configRepository.settings.collectAsState()
    val sites by container.siteRepository.sites.collectAsState()
    val lanState by container.lanState.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        GradientTopBar(
            title = "設定",
            subtitle = "外觀 · 連線 · 遠端遙控",
            trailing = {
                AppButton(
                    text = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { nav.popBackStack() },
                    primary = false,
                    iconOnly = windowSize.isCompact,
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = windowSize.pagePadding(), vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(windowSize.sectionGap()),
        ) {
            Card {
                SectionHeader(title = "影片網格顯示")
                Text(
                    "切換卡片比例與欄數",
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ViewMode.entries.toList()) { mode ->
                        FocusableTag(
                            text = "${mode.emoji} ${mode.label}",
                            selected = mode == settings.viewMode,
                            onClick = {
                                scope.launch { container.configRepository.updateViewMode(mode) }
                            },
                        )
                    }
                }
            }

            Card {
                SectionHeader(title = "內容")
                Text(
                    "站點 ${sites.size}",
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AppButton(
                    text = "站點管理",
                    icon = Icons.Filled.Dns,
                    onClick = { nav.navigate(Routes.Setup) },
                )
            }

            Card {
                SectionHeader(title = "疑難排解")
                val logCount by tw.pp.kazi.util.LogBuffer.entries.collectAsState()
                Text(
                    "目前有 ${logCount.size} 筆日誌紀錄",
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AppButton(
                    text = "檢視錯誤紀錄",
                    icon = Icons.Filled.BugReport,
                    onClick = { nav.navigate(Routes.Logs) },
                    primary = false,
                )
            }

            Card {
                SectionHeader(title = "遠端遙控")
                Text(
                    if (lanState.running) "已啟用：${lanState.url ?: "-"}" else "未啟用",
                    color = if (lanState.running) AppColors.Success else AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AppButton(
                    text = "開啟遠端遙控設定",
                    icon = Icons.Filled.QrCode2,
                    onClick = { nav.navigate(Routes.LanShare) },
                )
            }

            Card {
                SectionHeader(title = "關於")
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "咔滋影院",
                        color = AppColors.OnBg,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppColors.Primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "v${tw.pp.kazi.BuildConfig.VERSION_NAME}",
                            color = AppColors.OnBg,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "基於 MacCMS 開放 API 的資源聚合播放器，原生 Android 版本",
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.BgCard)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

