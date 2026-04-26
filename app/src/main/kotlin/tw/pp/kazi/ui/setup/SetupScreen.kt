package tw.pp.kazi.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import tw.pp.kazi.data.CheckStatusKeys
import tw.pp.kazi.data.HealthStatus
import tw.pp.kazi.data.MoveDirection
import tw.pp.kazi.data.Site
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.GradientTopBar
import tw.pp.kazi.ui.components.SectionHeader
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.sectionGap
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SetupScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val compact = windowSize.isCompact
    val sites by container.siteRepository.sites.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var newUrl by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var successMsg by remember { mutableStateOf<String?>(null) }
    var busySiteId by remember { mutableStateOf<Long?>(null) }
    var includeDisabled by remember { mutableStateOf(false) }
    var checkSummary by remember { mutableStateOf<String?>(null) }
    var batchChecking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Site?>(null) }
    var transferMsg by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<tw.pp.kazi.data.ImportPreview?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientTopBar(
            title = "站點管理",
            subtitle = "新增 / 編輯 / 排序",
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

        val mainContent: @Composable ColumnScope.() -> Unit = {
            BatchOpsCard(
                includeDisabled = includeDisabled,
                onToggleInclude = { includeDisabled = !includeDisabled },
                batchChecking = batchChecking,
                summary = checkSummary,
                sitesEnabled = sites.isNotEmpty(),
                transferMsg = transferMsg,
                onCheckAll = {
                    scope.launch {
                        batchChecking = true
                        val targets = if (includeDisabled) sites else sites.filter { it.enabled }
                        val results = container.macCmsApi.checkSitesParallel(targets)
                        results.forEach {
                            container.siteRepository.recordCheckResult(
                                it.siteId,
                                it.status == HealthStatus.Success,
                            )
                        }
                        val ok = results.count { it.status == HealthStatus.Success }
                        checkSummary = "完成：${ok} 成功 / ${results.size - ok} 失敗"
                        batchChecking = false
                    }
                },
                onLanShare = { nav.navigate(Routes.LanShare) },
                onExport = {
                    val json = container.siteRepository.exportToJson()
                    clipboard.setText(AnnotatedString(json))
                    transferMsg = "已複製 ${sites.size} 個站台到剪貼簿"
                },
                onImport = {
                    val raw = clipboard.getText()?.text.orEmpty()
                    val preview = container.siteRepository.parseImport(raw)
                    if (preview.errorMessage != null) {
                        transferMsg = preview.errorMessage
                    } else if (preview.toAdd.isEmpty() && preview.skipped.isEmpty()) {
                        transferMsg = "剪貼簿沒有可匯入的站台"
                    } else {
                        importPreview = preview
                        transferMsg = null
                    }
                },
            )

            AddSiteCard(
                newUrl = newUrl,
                newName = newName,
                onUrlChange = { newUrl = it; errorMsg = null },
                onNameChange = { newName = it },
                errorMsg = errorMsg,
                successMsg = successMsg,
                onAdd = {
                    if (newUrl.isBlank()) {
                        errorMsg = "請輸入 URL"
                        return@AddSiteCard
                    }
                    scope.launch {
                        val r = container.siteRepository.addSite(
                            newUrl.trim(),
                            newName.trim().ifEmpty { null },
                        )
                        r.fold(
                            onSuccess = {
                                newUrl = ""; newName = ""
                                successMsg = "已新增 ${it.name}"
                                errorMsg = null
                            },
                            onFailure = { errorMsg = it.message; successMsg = null },
                        )
                    }
                },
                onScan = { nav.navigate(Routes.ScanSites) },
            )
        }

        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = windowSize.pagePadding(), vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(windowSize.sectionGap()),
            ) {
                mainContent()
                SiteListSection(
                    sites = sites,
                    busySiteId = busySiteId,
                    onEdit = { editing = it },
                    onToggle = { s -> scope.launch { container.siteRepository.toggleEnabled(s.id, !s.enabled) } },
                    onMoveUp = { s -> scope.launch { container.siteRepository.moveSite(s.id, MoveDirection.Up) } },
                    onMoveDown = { s -> scope.launch { container.siteRepository.moveSite(s.id, MoveDirection.Down) } },
                    onDelete = { s -> scope.launch { container.siteRepository.deleteSite(s.id) } },
                    onCheck = { s ->
                        scope.launch {
                            busySiteId = s.id
                            val r = container.macCmsApi.checkSiteHealth(s)
                            container.siteRepository.recordCheckResult(s.id, r.status == HealthStatus.Success)
                            busySiteId = null
                        }
                    },
                    compact = true,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = windowSize.pagePadding(), vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(LEFT_COL_WIDTH)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(windowSize.sectionGap()),
                    content = mainContent,
                )
                Column(modifier = Modifier.weight(1f)) {
                    SiteListSection(
                        sites = sites,
                        busySiteId = busySiteId,
                        onEdit = { editing = it },
                        onToggle = { s -> scope.launch { container.siteRepository.toggleEnabled(s.id, !s.enabled) } },
                        onMoveUp = { s -> scope.launch { container.siteRepository.moveSite(s.id, MoveDirection.Up) } },
                        onMoveDown = { s -> scope.launch { container.siteRepository.moveSite(s.id, MoveDirection.Down) } },
                        onDelete = { s -> scope.launch { container.siteRepository.deleteSite(s.id) } },
                        onCheck = { s ->
                            scope.launch {
                                busySiteId = s.id
                                val r = container.macCmsApi.checkSiteHealth(s)
                                container.siteRepository.recordCheckResult(s.id, r.status == HealthStatus.Success)
                                busySiteId = null
                            }
                        },
                        compact = false,
                    )
                }
            }
        }
    }

    editing?.let { site ->
        EditSiteDialog(
            site = site,
            onDismiss = { editing = null },
            onConfirm = { name, url, sslVerify ->
                scope.launch {
                    val r = container.siteRepository.editSite(site.id, name, url, sslVerify)
                    r.onSuccess { editing = null }
                    r.onFailure { errorMsg = it.message }
                }
            },
        )
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            onDismiss = { importPreview = null },
            onConfirm = {
                scope.launch {
                    val r = container.siteRepository.importApply(preview.toAdd)
                    transferMsg = "匯入完成：成功 ${r.added}" +
                        (if (r.failed > 0) " / 失敗 ${r.failed}" else "") +
                        (if (preview.skipped.isNotEmpty()) " / 略過 ${preview.skipped.size}" else "")
                    importPreview = null
                }
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColumnScope.AddSiteCard(
    newUrl: String,
    newName: String,
    onUrlChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    errorMsg: String?,
    successMsg: String?,
    onAdd: () -> Unit,
    onScan: () -> Unit,
) {
    SectionHeader(title = "新增站點")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.BgCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FormField(
            label = "站點 URL",
            placeholder = "https://example.com",
            value = newUrl,
            onValueChange = onUrlChange,
            keyboardType = KeyboardType.Uri,
        )
        FormField(
            label = "名稱（可留空自動命名）",
            placeholder = "選填",
            value = newName,
            onValueChange = onNameChange,
        )
        errorMsg?.let {
            Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall)
        }
        successMsg?.let {
            Text(it, color = AppColors.Success, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(text = "新增", icon = Icons.Filled.Add, onClick = onAdd)
            AppButton(
                text = "掃描站台",
                icon = Icons.Filled.TravelExplore,
                onClick = onScan,
                primary = false,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColumnScope.BatchOpsCard(
    includeDisabled: Boolean,
    onToggleInclude: () -> Unit,
    batchChecking: Boolean,
    summary: String?,
    sitesEnabled: Boolean,
    transferMsg: String?,
    onCheckAll: () -> Unit,
    onLanShare: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SectionHeader(title = "批次操作")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.BgCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckboxRow(
            label = "包含已停用站點",
            checked = includeDisabled,
            onToggle = onToggleInclude,
        )
        AppButton(
            text = if (batchChecking) "並行檢查中⋯" else "檢查所有站點",
            icon = Icons.Filled.HealthAndSafety,
            onClick = onCheckAll,
            enabled = !batchChecking && sitesEnabled,
            primary = false,
            modifier = Modifier.fillMaxWidth(),
        )
        summary?.let {
            Text(it, color = AppColors.OnBgMuted, style = MaterialTheme.typography.bodySmall)
        }
        AppButton(
            text = "遠端遙控",
            icon = Icons.Filled.QrCode2,
            onClick = onLanShare,
            primary = false,
            modifier = Modifier.fillMaxWidth(),
        )
        AppButton(
            text = "匯出站點到剪貼簿",
            icon = Icons.Filled.ContentCopy,
            onClick = onExport,
            enabled = sitesEnabled,
            primary = false,
            modifier = Modifier.fillMaxWidth(),
        )
        AppButton(
            text = "從剪貼簿匯入站點",
            icon = Icons.Filled.ContentPaste,
            onClick = onImport,
            primary = false,
            modifier = Modifier.fillMaxWidth(),
        )
        transferMsg?.let {
            Text(it, color = AppColors.OnBgMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SiteListSection(
    sites: List<Site>,
    busySiteId: Long?,
    onEdit: (Site) -> Unit,
    onToggle: (Site) -> Unit,
    onMoveUp: (Site) -> Unit,
    onMoveDown: (Site) -> Unit,
    onDelete: (Site) -> Unit,
    onCheck: (Site) -> Unit,
    compact: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "現有站點（${sites.size}）")
        if (sites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.BgCard),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.DataObject, contentDescription = null,
                        tint = AppColors.OnBgDim, modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "尚無站點",
                        color = AppColors.OnBgMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sites.forEach { site ->
                        SiteRow(
                            site = site,
                            busy = busySiteId == site.id,
                            onEdit = { onEdit(site) },
                            onToggle = { onToggle(site) },
                            onMoveUp = { onMoveUp(site) },
                            onMoveDown = { onMoveDown(site) },
                            onDelete = { onDelete(site) },
                            onCheck = { onCheck(site) },
                            compact = true,
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sites, key = { it.id }) { site ->
                        SiteRow(
                            site = site,
                            busy = busySiteId == site.id,
                            onEdit = { onEdit(site) },
                            onToggle = { onToggle(site) },
                            onMoveUp = { onMoveUp(site) },
                            onMoveDown = { onMoveDown(site) },
                            onDelete = { onDelete(site) },
                            onCheck = { onCheck(site) },
                            compact = false,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EditSiteDialog(
    site: Site,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, sslVerify: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(site.name) }
    var url by remember { mutableStateOf(site.url) }
    var sslVerify by remember { mutableStateOf(site.sslVerify) }
    var localError by remember { mutableStateOf<String?>(null) }
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val dialogWidth = if (screenW < 560.dp) screenW - 32.dp else 480.dp

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(dialogWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.BgCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "編輯站點",
                color = AppColors.OnBg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            FormField(label = "名稱", placeholder = "自動命名", value = name, onValueChange = { name = it })
            FormField(
                label = "URL",
                placeholder = "https://example.com",
                value = url,
                onValueChange = { url = it; localError = null },
                keyboardType = KeyboardType.Uri,
            )
            CheckboxRow(label = "驗證 SSL 憑證", checked = sslVerify, onToggle = { sslVerify = !sslVerify })
            localError?.let {
                Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(text = "取消", onClick = onDismiss, primary = false)
                AppButton(
                    text = "儲存",
                    icon = Icons.Filled.Save,
                    onClick = {
                        if (url.isBlank()) {
                            localError = "URL 不能為空"
                            return@AppButton
                        }
                        onConfirm(name, url, sslVerify)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CheckboxRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                2.dp,
                if (focused) AppColors.FocusRing else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onToggle() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) AppColors.Primary else Color(0x33FFFFFF))
                .border(
                    2.dp,
                    if (checked) AppColors.Primary else Color(0x66FFFFFF),
                    RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Text(label, color = AppColors.OnBg, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FormField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
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
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SiteRow(
    site: Site,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
    compact: Boolean,
) {
    val statusColor = when (site.checkStatus) {
        CheckStatusKeys.SUCCESS -> AppColors.Success
        CheckStatusKeys.FAILED -> AppColors.Error
        else -> AppColors.OnBgDim
    }

    if (compact) {
        // 手機：垂直佈局，上面是資訊，下面是一列可捲動的按鈕
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.BgCard)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor),
                )
                Text(
                    site.name,
                    color = AppColors.OnBg,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!site.enabled) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0x33FFFFFF))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text("停用", color = AppColors.OnBgMuted, fontSize = 10.sp)
                    }
                }
            }
            Text(
                site.url,
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    AppButton(
                        text = if (busy) "檢查中" else "檢查",
                        icon = if (busy) Icons.Filled.HourglassTop else Icons.Filled.HealthAndSafety,
                        onClick = onCheck,
                        enabled = !busy,
                        primary = false,
                        iconOnly = true,
                    )
                }
                item { AppButton(text = "編輯", icon = Icons.Filled.Edit, onClick = onEdit, primary = false, iconOnly = true) }
                item { AppButton(text = "上移", icon = Icons.Filled.ArrowUpward, onClick = onMoveUp, primary = false, iconOnly = true) }
                item { AppButton(text = "下移", icon = Icons.Filled.ArrowDownward, onClick = onMoveDown, primary = false, iconOnly = true) }
                item {
                    AppButton(
                        text = if (site.enabled) "停用" else "啟用",
                        icon = if (site.enabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        onClick = onToggle,
                        primary = false,
                        iconOnly = true,
                    )
                }
                item { tw.pp.kazi.ui.components.ConfirmDeleteButton(text = "刪除", icon = Icons.Filled.Delete, onConfirm = onDelete, iconOnly = true) }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.BgCard)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(statusColor),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        site.name,
                        color = AppColors.OnBg,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!site.enabled) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0x33FFFFFF))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text("停用", color = AppColors.OnBgMuted, fontSize = 10.sp)
                        }
                    }
                }
                Text(
                    site.url,
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            AppButton(
                text = if (busy) "檢查中" else "檢查",
                icon = if (busy) Icons.Filled.HourglassTop else Icons.Filled.HealthAndSafety,
                onClick = onCheck,
                enabled = !busy,
                primary = false,
                iconOnly = true,
            )
            AppButton(text = "編輯", icon = Icons.Filled.Edit, onClick = onEdit, primary = false, iconOnly = true)
            AppButton(text = "上移", icon = Icons.Filled.ArrowUpward, onClick = onMoveUp, primary = false, iconOnly = true)
            AppButton(text = "下移", icon = Icons.Filled.ArrowDownward, onClick = onMoveDown, primary = false, iconOnly = true)
            AppButton(
                text = if (site.enabled) "停用" else "啟用",
                icon = if (site.enabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                onClick = onToggle,
                primary = false,
                iconOnly = true,
            )
            tw.pp.kazi.ui.components.ConfirmDeleteButton(text = "刪除", icon = Icons.Filled.Delete, onConfirm = onDelete, iconOnly = true)
        }
    }
}

private val LEFT_COL_WIDTH = 360.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportPreviewDialog(
    preview: tw.pp.kazi.data.ImportPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val dialogWidth = if (screenW < 560.dp) screenW - 32.dp else 480.dp
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(dialogWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.BgCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "匯入站點預覽",
                color = AppColors.OnBg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "新增 ${preview.toAdd.size} 個" +
                    (if (preview.skipped.isNotEmpty()) "（略過 ${preview.skipped.size} 個重複的）" else ""),
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                preview.toAdd.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x10FFFFFF))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(AppColors.Success),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.name,
                                color = AppColors.OnBg,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                item.url,
                                color = AppColors.OnBgMuted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
                preview.skipped.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x08FFFFFF))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(AppColors.OnBgDim),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.name + "（已存在）",
                                color = AppColors.OnBgDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                item.url,
                                color = AppColors.OnBgDim,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(text = "取消", onClick = onDismiss, primary = false)
                AppButton(
                    text = "確定匯入",
                    icon = Icons.Filled.Check,
                    onClick = onConfirm,
                    enabled = preview.toAdd.isNotEmpty(),
                )
            }
        }
    }
}
