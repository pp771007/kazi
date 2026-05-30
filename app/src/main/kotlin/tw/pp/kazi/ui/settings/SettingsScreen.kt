package tw.pp.kazi.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.components.SectionHeader
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.sectionGap
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

import kotlinx.coroutines.launch

private const val GITHUB_REPO_URL = "https://github.com/pp771007/kazi"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val context = LocalContext.current
    val sites by container.siteRepository.sites.collectAsState()
    val settings by container.configRepository.settings.collectAsState()

    // 進設定頁預設 focus 到「站點管理」(主要操作)；不要讓 UpdateSection 預設搶走 focus。
    // 只在 TV 跑：手機觸控不需要 visible focus 起點
    val siteManagementFocus = remember { FocusRequester() }
    LaunchedEffect(windowSize) {
        if (!windowSize.isTv) return@LaunchedEffect
        kotlinx.coroutines.delay(50)  // 等 layout 把 focusable attach 完成
        runCatching { siteManagementFocus.requestFocus() }
    }

    ScreenScaffold(
        title = "設定",
        subtitle = "站點 · 紀錄",
        onBack = { nav.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = windowSize.pagePadding(), vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(windowSize.sectionGap()),
        ) {
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
                    modifier = Modifier.focusRequester(siteManagementFocus),
                )
            }

            // 更新放在較上面(常用),且 UpdateSection 的焦點亂跳問題已修(keepFocusable),不必再塞底部。
            Card {
                SectionHeader(title = "更新")
                UpdateSection()
            }

            Card {
                SectionHeader(title = "帳號同步")
                if (settings.syncEnabled) {
                    // 已綁定:只顯示狀態與操作,不再顯示網址/密碼(要改先解除綁定)
                    Text(
                        "已綁定:${settings.syncNickname.ifBlank { "(同步中…)" }}",
                        color = AppColors.OnBg,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "伺服器:${settings.syncServerUrl}",
                        color = AppColors.OnBgMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    // 不再放「立即同步」:進歷史/收藏頁已自動同步,失敗才提示(見 SyncOnEnter)。
                    AppButton(
                        text = "解除綁定",
                        icon = Icons.Filled.LinkOff,
                        primary = false,
                        onClick = {
                            container.appScope.launch { container.unbindSync() }
                        },
                    )
                } else {
                    // 未綁定:填網址+密碼換 token(密碼只用一次,不長存)
                    Text(
                        "填網頁版網址 + 密碼,觀看歷史與收藏就會跟網頁、其他裝置同步。",
                        color = AppColors.OnBgMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    var url by remember(settings.syncServerUrl) { mutableStateOf(settings.syncServerUrl) }
                    var pw by remember { mutableStateOf("") }
                    var saveMsg by remember { mutableStateOf<String?>(null) }

                    SyncField(
                        label = "伺服器網址",
                        placeholder = "https://你的網址.vercel.app",
                        value = url,
                        onValueChange = { url = it; saveMsg = null },
                    )
                    SyncField(
                        label = "密碼",
                        placeholder = "網頁登入密碼",
                        value = pw,
                        onValueChange = { pw = it; saveMsg = null },
                        isPassword = true,
                    )
                    AppButton(
                        text = saveMsg ?: "儲存並測試連線",
                        icon = Icons.Filled.Save,
                        onClick = {
                            saveMsg = "連線中…"
                            container.appScope.launch {
                                val ok = container.saveAndTestSync(url.trim(), pw)
                                saveMsg = if (ok) "已連線,開始同步" else "連線失敗,請檢查網址與密碼"
                            }
                        },
                    )
                }
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
                Spacer(Modifier.height(8.dp))
                AppButton(
                    text = "GitHub 專案",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    primary = false,
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
        }
    }
}

// 同步設定的輸入框(對齊站點設定的 FormField 樣式;密碼欄可遮蔽)
@Composable
private fun SyncField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = AppColors.OnBgMuted, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D0D15))
                .border(
                    2.dp,
                    if (focused) AppColors.FocusRing else Color(0x22FFFFFF),
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = AppColors.OnBg, fontSize = 15.sp),
                cursorBrush = SolidColor(AppColors.Primary),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, color = AppColors.OnBgDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    inner()
                },
            )
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

