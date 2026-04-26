package tw.pp.kazi.ui.logs

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import tw.pp.kazi.BuildConfig
import tw.pp.kazi.util.LogBuffer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Filter { All, Warn, Error }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LogScreen() {
    val nav = LocalNavController.current
    val container = LocalAppContainer.current
    val windowSize = LocalWindowSize.current
    val entries by LogBuffer.entries.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var filter by remember { mutableStateOf(Filter.All) }

    val visible = remember(entries, filter) {
        when (filter) {
            Filter.All -> entries.asReversed()
            Filter.Warn -> entries.filter { it.level == LogBuffer.Level.W || it.level == LogBuffer.Level.E }.asReversed()
            Filter.Error -> entries.filter { it.level == LogBuffer.Level.E }.asReversed()
        }
    }

    ScreenScaffold(
        title = "錯誤紀錄",
        subtitle = "${entries.size} 筆，最多保留 300 筆",
        onBack = { nav.popBackStack() },
        trailing = {
            AppButton(
                text = "複製全部",
                icon = Icons.Filled.ContentCopy,
                iconOnly = windowSize.isCompact,
                onClick = {
                    val text = buildReport(entries, container)
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "已複製 ${entries.size} 筆紀錄到剪貼簿", Toast.LENGTH_SHORT).show()
                },
                enabled = entries.isNotEmpty(),
            )
            tw.pp.kazi.ui.components.ConfirmDeleteButton(
                text = "清空",
                icon = Icons.Filled.DeleteSweep,
                iconOnly = windowSize.isCompact,
                onConfirm = { LogBuffer.clear() },
                enabled = entries.isNotEmpty(),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = windowSize.pagePadding(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip("全部 ${entries.size}", filter == Filter.All) { filter = Filter.All }
            val warnCount = entries.count { it.level == LogBuffer.Level.W || it.level == LogBuffer.Level.E }
            FilterChip("警告+錯誤 $warnCount", filter == Filter.Warn) { filter = Filter.Warn }
            val errCount = entries.count { it.level == LogBuffer.Level.E }
            FilterChip("僅錯誤 $errCount", filter == Filter.Error) { filter = Filter.Error }
        }

        if (visible.isEmpty()) {
            EmptyState(
                title = if (entries.isEmpty()) "還沒有任何日誌" else "沒有符合的紀錄",
                subtitle = if (entries.isEmpty())
                    "發生問題時，錯誤會自動記錄在這裡"
                else "切換上面的篩選或清空再用",
                icon = Icons.AutoMirrored.Filled.HelpOutline,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = windowSize.pagePadding(),
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visible) { entry -> LogRow(entry) }
            }
        }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusableTag(text = text, selected = selected, onClick = onClick)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogRow(entry: LogBuffer.Entry) {
    val levelColor = when (entry.level) {
        LogBuffer.Level.D -> AppColors.OnBgDim
        LogBuffer.Level.I -> AppColors.OnBgMuted
        LogBuffer.Level.W -> AppColors.Warning
        LogBuffer.Level.E -> AppColors.Error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.BgCard)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(levelColor)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    entry.level.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
            Text(
                SHORT_TIME.format(Date(entry.timestamp)),
                color = AppColors.OnBgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            entry.message,
            color = AppColors.OnBg,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (entry.stackTrace != null) {
            Text(
                entry.stackTrace,
                color = AppColors.OnBgMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = STACK_TRACE_PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildReport(
    entries: List<LogBuffer.Entry>,
    container: tw.pp.kazi.AppContainer,
): String {
    val sb = StringBuilder()
    sb.appendLine("========== 咔滋影院 錯誤紀錄 ==========")
    sb.appendLine("時間：${FULL_TIME.format(Date())}")
    sb.appendLine("App：${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
    sb.appendLine("Android：${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    sb.appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}")
    val sites = container.siteRepository.sites.value
    val enabled = sites.count { it.enabled }
    sb.appendLine("站點：${sites.size} 個（啟用 $enabled）")
    val lan = container.lanState.value
    sb.appendLine("遠端遙控：${if (lan.running) "啟用於 ${lan.url}" else "未啟用"}")
    sb.appendLine("紀錄筆數：${entries.size}")
    sb.appendLine("==========================================")
    sb.appendLine()
    entries.forEach { entry ->
        sb.appendLine(LogBuffer.format(entry))
        sb.appendLine()
    }
    return sb.toString()
}

private const val STACK_TRACE_PREVIEW_MAX_LINES = 12

private val SHORT_TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
private val FULL_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
