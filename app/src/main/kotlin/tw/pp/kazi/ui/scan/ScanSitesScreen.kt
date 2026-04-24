package tw.pp.kazi.ui.scan

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tw.pp.kazi.data.ProbeResult
import tw.pp.kazi.data.cleanBaseUrl
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.GradientTopBar
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val GOOGLE_SEARCH_URL =
    "https://www.google.com/search?q=inurl:/api.php/provide/vod/"

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/**
 * 抓當前 WebView 頁面上所有 href，過濾含 `api.php/provide/vod` 的連結。
 * 處理 Google 搜尋結果頁的 /url?q= 重導格式。
 */
private const val HARVEST_JS = """
(function(){
  try {
    var anchors = document.querySelectorAll('a');
    var out = [];
    var pat = /api\.php\/provide\/vod/i;
    for (var i = 0; i < anchors.length; i++) {
      var href = anchors[i].href;
      if (!href) continue;
      var url = href;
      try {
        var u = new URL(href, location.href);
        if (/google\./.test(u.hostname) && u.pathname === '/url') {
          var q = u.searchParams.get('q') || u.searchParams.get('url');
          if (q) url = q;
        }
      } catch (e) {}
      if (pat.test(url)) out.push(url);
    }
    return JSON.stringify(out);
  } catch (e) {
    return JSON.stringify([]);
  }
})();
"""

private enum class CandidateStatus { Pending, Ok, Fail }

private data class Candidate(
    val url: String,
    val status: CandidateStatus,
    val resolvedName: String?,
    val message: String,
    val selected: Boolean,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ScanSitesScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val existingSites by container.siteRepository.sites.collectAsState()
    val existingUrls = remember(existingSites) {
        existingSites.map { it.url.lowercase() }.toSet()
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var harvesting by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<Candidate>>(emptyList()) }
    var addingBatch by remember { mutableStateOf(false) }

    val selectedCount = candidates.count { it.selected && it.status == CandidateStatus.Ok }

    fun harvestPage() {
        val wv = webView ?: return
        if (harvesting) return
        harvesting = true
        statusMsg = "正在抓取本頁連結..."
        wv.evaluateJavascript(HARVEST_JS) { raw ->
            val urls = parseHarvestResult(raw)
                .mapNotNull { cleanBaseUrl(it) }
                .map { it.lowercase() to it }
                .distinctBy { it.first }
                .map { it.second }
                .filter { it.lowercase() !in existingUrls }
                .filter { candUrl ->
                    candidates.none { it.url.equals(candUrl, ignoreCase = true) }
                }

            if (urls.isEmpty()) {
                harvesting = false
                statusMsg = "本頁沒有新站點（可能已加入或已在候選池）"
                return@evaluateJavascript
            }

            val newItems = urls.map {
                Candidate(
                    url = it,
                    status = CandidateStatus.Pending,
                    resolvedName = null,
                    message = "驗證中...",
                    selected = false,
                )
            }
            candidates = candidates + newItems
            statusMsg = "本頁新增 ${newItems.size} 個候選，驗證中..."

            scope.launch {
                val results = withContext(Dispatchers.IO) {
                    newItems.map { item ->
                        async { item.url to container.siteScanner.probe(item.url) }
                    }.awaitAll()
                }
                candidates = candidates.map { c ->
                    val matched = results.firstOrNull { it.first == c.url } ?: return@map c
                    c.applyProbe(matched.second)
                }
                val ok = results.count { it.second.healthy }
                val fail = results.size - ok
                statusMsg = "本頁完成：${ok} 個可用 / ${fail} 個無效"
                harvesting = false
            }
        }
    }

    fun reloadGoogle() {
        webView?.loadUrl(GOOGLE_SEARCH_URL)
    }

    fun clearCandidates() {
        candidates = emptyList()
        statusMsg = "已清空候選池"
    }

    fun addSelected() {
        if (addingBatch) return
        val toAdd = candidates.filter { it.selected && it.status == CandidateStatus.Ok }
        if (toAdd.isEmpty()) return
        addingBatch = true
        scope.launch {
            var ok = 0
            var fail = 0
            toAdd.forEach { c ->
                val r = container.siteRepository.addSite(c.url, c.resolvedName)
                if (r.isSuccess) ok++ else fail++
            }
            val addedUrls = toAdd.map { it.url.lowercase() }.toSet()
            candidates = candidates.filter { it.url.lowercase() !in addedUrls }
            statusMsg = "已加入 $ok 個站點${if (fail > 0) "（$fail 個失敗）" else ""}"
            addingBatch = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientTopBar(
            title = "掃描站台",
            subtitle = "Google 搜尋 MacCMS 介面",
            trailing = {
                AppButton(
                    text = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { nav.popBackStack() },
                    primary = false,
                    iconOnly = true,
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                text = if (harvesting) "抓取中..." else "抓取本頁",
                icon = Icons.Filled.TravelExplore,
                onClick = ::harvestPage,
                enabled = !harvesting && webView != null,
            )
            AppButton(
                text = "重載",
                icon = Icons.Filled.Refresh,
                onClick = ::reloadGoogle,
                primary = false,
            )
        }

        AndroidWebView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f),
            onCreated = { webView = it },
        )

        CandidatePanel(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f)
                .background(AppColors.Surface),
            candidates = candidates,
            statusMsg = statusMsg,
            selectedCount = selectedCount,
            addingBatch = addingBatch,
            onToggle = { c ->
                candidates = candidates.map {
                    if (it.url == c.url && it.status == CandidateStatus.Ok) it.copy(selected = !it.selected)
                    else it
                }
            },
            onClear = ::clearCandidates,
            onAddSelected = ::addSelected,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AndroidWebView(
    modifier: Modifier,
    onCreated: (WebView) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = CHROME_UA
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                webViewClient = WebViewClient()
                loadUrl(GOOGLE_SEARCH_URL)
                onCreated(this)
            }
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CandidatePanel(
    modifier: Modifier,
    candidates: List<Candidate>,
    statusMsg: String?,
    selectedCount: Int,
    addingBatch: Boolean,
    onToggle: (Candidate) -> Unit,
    onClear: () -> Unit,
    onAddSelected: () -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "候選站點（${candidates.size}）",
                color = AppColors.OnBg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "清空",
                icon = Icons.Filled.Clear,
                onClick = onClear,
                enabled = candidates.isNotEmpty() && !addingBatch,
                primary = false,
            )
            AppButton(
                text = if (addingBatch) "加入中..." else "加入所選 ($selectedCount)",
                icon = Icons.Filled.Add,
                onClick = onAddSelected,
                enabled = selectedCount > 0 && !addingBatch,
            )
        }

        statusMsg?.let {
            Text(
                text = it,
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (candidates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "尚未抓取 — 在上方瀏覽 Google 結果後，按「抓取本頁」",
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(candidates, key = { it.url }) { c ->
                    CandidateRow(candidate = c, onToggle = { onToggle(c) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CandidateRow(candidate: Candidate, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val statusColor = when (candidate.status) {
        CandidateStatus.Pending -> AppColors.OnBgDim
        CandidateStatus.Ok -> AppColors.Success
        CandidateStatus.Fail -> AppColors.Error
    }
    val clickable = candidate.status == CandidateStatus.Ok

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.BgCard)
            .border(
                2.dp,
                if (focused) AppColors.FocusRing else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .focusable(enabled = clickable, interactionSource = interaction)
            .clickable(enabled = clickable, interactionSource = interaction, indication = null) {
                onToggle()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (candidate.selected) AppColors.Primary else Color(0x33FFFFFF))
                .border(
                    2.dp,
                    if (candidate.selected) AppColors.Primary else Color(0x66FFFFFF),
                    RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (candidate.selected) {
                Icon(
                    Icons.Filled.Check,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor),
                )
                Text(
                    text = candidate.resolvedName ?: candidate.url.removePrefix("http://").removePrefix("https://"),
                    color = if (clickable) AppColors.OnBg else AppColors.OnBgDim,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Text(
                text = candidate.url,
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            if (candidate.status != CandidateStatus.Ok) {
                Text(
                    text = candidate.message,
                    color = if (candidate.status == CandidateStatus.Fail) AppColors.Error else AppColors.OnBgDim,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private fun Candidate.applyProbe(probe: ProbeResult): Candidate = copy(
    status = if (probe.healthy) CandidateStatus.Ok else CandidateStatus.Fail,
    resolvedName = probe.name,
    message = probe.message,
    selected = selected || probe.healthy,
)

private fun parseHarvestResult(raw: String?): List<String> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    // evaluateJavascript 回傳的字串是 JSON-encoded string, e.g. "\"[\\\"https://...\\\"]\""
    // 先解一層 string，再 parse array
    return runCatching {
        val outer = Json.parseToJsonElement(raw)
        val inner = outer.jsonPrimitive.contentOrNull ?: return emptyList()
        (Json.parseToJsonElement(inner) as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
    }.getOrDefault(emptyList())
}
