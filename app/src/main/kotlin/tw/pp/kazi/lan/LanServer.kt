package tw.pp.kazi.lan

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tw.pp.kazi.data.AppJson
import tw.pp.kazi.data.MoveDirection
import tw.pp.kazi.data.RemoteSearchRequest
import tw.pp.kazi.data.Site
import tw.pp.kazi.data.SiteRepository
import tw.pp.kazi.util.Logger
import android.content.Context
import tw.pp.kazi.util.ChineseConverter
import java.io.IOException

class LanServer(
    port: Int,
    private val siteRepository: SiteRepository,
    private val onRemoteSearch: (RemoteSearchRequest) -> Boolean,
    private val appContext: Context,
) : NanoHTTPD(port) {

    // 注意：handler 內呼叫的 SiteRepository.* 都是 suspend + 自帶 mutex，
    // 所以這裡不需要再額外加 outer lock；先前那層是冗餘的。

    fun safeStart(): Boolean = try {
        start(SOCKET_READ_TIMEOUT, false)
        Logger.i("LanServer started on port $listeningPort")
        true
    } catch (e: IOException) {
        Logger.w("LanServer start failed: ${e.message}")
        false
    }

    fun safeStop() {
        runCatching { stop() }
        Logger.i("LanServer stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            val method = session.method
            when {
                uri == PATH_ROOT -> indexPage()
                uri == PATH_API_SITES && method == Method.GET -> listSites()
                uri == PATH_API_SITES && method == Method.POST -> addSite(session)
                uri == PATH_API_SITES_IMPORT && method == Method.POST -> importSites(session)
                uri == PATH_API_REMOTE_SEARCH && method == Method.POST -> remoteSearch(session)
                uri == PATH_API_T2S && method == Method.POST -> t2s(session)
                uri.startsWith(PATH_API_SITE_PREFIX) && uri.endsWith(PATH_MOVE_SUFFIX) -> moveSite(session)
                uri.startsWith(PATH_API_SITE_PREFIX) && method == Method.DELETE -> deleteSite(session)
                uri.startsWith(PATH_API_SITE_PREFIX) && method == Method.PUT -> updateSite(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: TruncatedBodyException) {
            Logger.w("LanServer truncated body: ${e.message}")
            badRequest("Incomplete request body: ${e.message}")
        } catch (e: Exception) {
            Logger.e("LanServer handler error", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, buildJsonObject {
                put("status", STATUS_ERROR)
                put("message", e.message ?: "Unknown error")
            })
        }
    }

    private fun listSites(): Response {
        val sites = siteRepository.sites.value.sortedBy { it.order }
        val arr = buildJsonArray {
            sites.forEach { add(AppJson.encodeToJsonElement(Site.serializer(), it)) }
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, arr.toString())
    }

    private fun addSite(session: IHTTPSession): Response = runBlocking {
        val body = readBody(session)
        val obj = parseJsonObject(body) ?: return@runBlocking badRequest("Invalid JSON")
        val url = (obj["url"] as? JsonPrimitive)?.content
            ?: return@runBlocking badRequest("url required")
        val name = (obj["name"] as? JsonPrimitive)?.content
        val result = siteRepository.addSite(url, name)
        result.fold(
            onSuccess = {
                jsonResponse(Response.Status.CREATED, buildJsonObject {
                    put("status", STATUS_SUCCESS)
                    put("site", AppJson.encodeToJsonElement(Site.serializer(), it))
                })
            },
            onFailure = { badRequest(it.message ?: "add failed") },
        )
    }

    private fun updateSite(session: IHTTPSession): Response = runBlocking {
        val id = session.uri.removePrefix(PATH_API_SITE_PREFIX).toLongOrNull()
            ?: return@runBlocking badRequest("Invalid id")
        val body = readBody(session)
        val obj = parseJsonObject(body) ?: return@runBlocking badRequest("Invalid JSON")
        val existing = siteRepository.sites.value.firstOrNull { it.id == id }
            ?: return@runBlocking badRequest("Not found", Response.Status.NOT_FOUND)
        val updated = existing.copy(
            name = (obj["name"] as? JsonPrimitive)?.content ?: existing.name,
            url = (obj["url"] as? JsonPrimitive)?.content ?: existing.url,
            enabled = (obj["enabled"] as? JsonPrimitive)?.content?.toBoolean() ?: existing.enabled,
            sslVerify = (obj["ssl_verify"] as? JsonPrimitive)?.content?.toBoolean() ?: existing.sslVerify,
        )
        siteRepository.updateSite(updated)
        jsonResponse(Response.Status.OK, buildJsonObject { put("status", STATUS_SUCCESS) })
    }

    private fun deleteSite(session: IHTTPSession): Response = runBlocking {
        val id = session.uri.removePrefix(PATH_API_SITE_PREFIX).toLongOrNull()
            ?: return@runBlocking badRequest("Invalid id")
        siteRepository.deleteSite(id)
        jsonResponse(Response.Status.OK, buildJsonObject { put("status", STATUS_SUCCESS) })
    }

    private fun moveSite(session: IHTTPSession): Response = runBlocking {
        val id = session.uri.removePrefix(PATH_API_SITE_PREFIX).removeSuffix(PATH_MOVE_SUFFIX).toLongOrNull()
            ?: return@runBlocking badRequest("Invalid id")
        val body = readBody(session)
        val obj = parseJsonObject(body) ?: return@runBlocking badRequest("Invalid JSON")
        val direction = when ((obj["direction"] as? JsonPrimitive)?.content) {
            "up" -> MoveDirection.Up
            "down" -> MoveDirection.Down
            else -> return@runBlocking badRequest("direction must be up or down")
        }
        siteRepository.moveSite(id, direction)
        jsonResponse(Response.Status.OK, buildJsonObject { put("status", STATUS_SUCCESS) })
    }

    private fun remoteSearch(session: IHTTPSession): Response {
        val body = readBody(session)
        val obj = parseJsonObject(body) ?: return badRequest("Invalid JSON")
        val keyword = (obj["keyword"] as? JsonPrimitive)?.content?.trim()
            ?: return badRequest("keyword required")
        if (keyword.isEmpty()) return badRequest("keyword required")
        val siteIds = (obj["site_ids"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            .orEmpty()
        val emitted = onRemoteSearch(RemoteSearchRequest(keyword, siteIds))
        return if (emitted) {
            jsonResponse(Response.Status.OK, buildJsonObject {
                put("status", STATUS_SUCCESS)
                put("message", "已送往 TV 盒")
            })
        } else {
            jsonResponse(Response.Status.OK, buildJsonObject {
                put("status", STATUS_ERROR)
                put("message", "TV 暫時無法接收，請稍後再試")
            })
        }
    }

    /**
     * 把手機 app 匯出的 JSON（[{name,url,ssl_verify,enabled}, ...]）丟過來，全部加進站點。
     */
    private fun importSites(session: IHTTPSession): Response = runBlocking {
        val body = readBody(session)
        val obj = parseJsonObject(body) ?: return@runBlocking badRequest("Invalid JSON")
        val rawText = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (rawText.isBlank()) return@runBlocking badRequest("text required")

        val preview = siteRepository.parseImport(rawText)
        preview.errorMessage?.let { return@runBlocking badRequest(it) }
        val result = siteRepository.importApply(preview.toAdd)
        jsonResponse(Response.Status.OK, buildJsonObject {
            put("status", STATUS_SUCCESS)
            put("added", result.added)
            put("failed", result.failed)
            put("skipped", preview.skipped.size)
            put("preview", buildJsonArray {
                preview.toAdd.forEach { add(buildJsonObject {
                    put("name", it.name)
                    put("url", it.url)
                }) }
            })
            put("skippedItems", buildJsonArray {
                preview.skipped.forEach { add(buildJsonObject {
                    put("name", it.name)
                    put("url", it.url)
                }) }
            })
        })
    }

    private fun t2s(session: IHTTPSession): Response {
        val body = readBody(session)
        val obj = parseJsonObject(body) ?: return badRequest("Invalid JSON")
        val text = (obj["text"] as? JsonPrimitive)?.content.orEmpty()
        val converted = ChineseConverter.toSimplified(text, appContext)
        return jsonResponse(Response.Status.OK, buildJsonObject {
            put("status", STATUS_SUCCESS)
            put("text", converted)
        })
    }

    // NanoHTTPD 2.3.1 的 parseBody 會用 Content-Type 的 charset decode，
    // 沒帶 charset 時 fallback 成 US-ASCII，UTF-8 中文會被破壞成 replacement char。
    // 直接從 inputStream 以 UTF-8 讀取，繞開這個行為。
    private class TruncatedBodyException(message: String) : Exception(message)

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (contentLength <= 0) return ""
        val buf = ByteArray(contentLength)
        val input = session.inputStream
        var off = 0
        while (off < contentLength) {
            val n = input.read(buf, off, contentLength - off)
            if (n < 0) break
            off += n
        }
        if (off < contentLength) {
            throw TruncatedBodyException("expected $contentLength bytes, got $off")
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun parseJsonObject(body: String): JsonObject? = runCatching {
        AppJson.parseToJsonElement(body) as? JsonObject
    }.getOrNull()

    private fun badRequest(msg: String, status: Response.Status = Response.Status.BAD_REQUEST): Response =
        jsonResponse(status, buildJsonObject {
            put("status", STATUS_ERROR)
            put("message", msg)
        })

    private fun jsonResponse(status: Response.Status, body: JsonObject): Response =
        newFixedLengthResponse(status, MIME_JSON, body.toString())

    private fun indexPage(): Response = newFixedLengthResponse(
        Response.Status.OK,
        MIME_HTML,
        INDEX_HTML,
    )

    companion object {
        private const val PATH_ROOT = "/"
        private const val PATH_API_SITES = "/api/sites"
        private const val PATH_API_SITES_IMPORT = "/api/sites/import"
        private const val PATH_API_REMOTE_SEARCH = "/api/remote_search"
        private const val PATH_API_T2S = "/api/t2s"
        private const val PATH_API_SITE_PREFIX = "/api/sites/"
        private const val PATH_MOVE_SUFFIX = "/move"

        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val MIME_HTML = "text/html; charset=utf-8"

        private const val STATUS_SUCCESS = "success"
        private const val STATUS_ERROR = "error"

        private val INDEX_HTML = """
<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>咔滋影院 · 遠端遙控</title>
<!-- favicon 用 SVG data URI 直接內嵌 🍿（跟 app 標題一致），瀏覽器 tab 上看得到 -->
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ctext y='.9em' font-size='90'%3E%F0%9F%8D%BF%3C/text%3E%3C/svg%3E">
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin: 0; font-family: -apple-system, system-ui, 'Segoe UI', sans-serif;
         background: linear-gradient(180deg,#0C0C16,#0A0A12); color:#eee;
         min-height: 100vh; padding: 20px; max-width: 720px; margin: 0 auto;}
  h1 { font-size: 22px; font-weight:600; margin: 4px 0 16px; color:#3B82F6;
       display:flex; align-items:center; gap:8px;}
  .tabs { display:flex; gap:6px; margin-bottom:18px; background:#15151f;
          border-radius:12px; padding:4px;}
  .tab { flex:1; text-align:center; padding:10px 8px; border-radius:10px;
         cursor:pointer; font-size:14px; color:#94A3B8; font-weight:500;
         transition: all 0.2s; border:none; background:transparent;}
  .tab.active { background:#3B82F6; color:#fff;}
  .panel { display:none;}
  .panel.active { display:block;}
  .card { background:#1E1E2E; border-radius:14px; padding:18px; margin-bottom:14px;
          border:1px solid #2a2a3e;}
  label { display:block; margin:8px 0 4px; font-size:13px; color:#94A3B8;}
  input[type=text], textarea {
    width:100%; padding:12px 14px; background:#0D0D15; color:#fff;
    border:2px solid #2a2a3e; border-radius:10px; font-size:15px; font-family:inherit;}
  input[type=text]:focus, textarea:focus { outline:none; border-color:#3B82F6;}
  textarea { resize:vertical; min-height:60px;}
  button { background:#3B82F6; color:#fff; border:none; padding:12px 18px;
           border-radius:10px; font-size:15px; font-weight:500; cursor:pointer;
           margin-top:10px; transition: all 0.15s;}
  button:hover, button:focus { background:#60A5FA; outline:none;}
  button:active { transform: scale(0.98);}
  button.danger { background:#EF4444;}
  button.danger:hover { background:#F87171;}
  button.secondary { background:#334155;}
  button.secondary:hover { background:#475569;}
  button.small { padding:6px 12px; font-size:13px;}
  button[disabled] { opacity:0.5; cursor:not-allowed;}
  ul { list-style:none; padding:0; margin:0;}
  li { display:flex; align-items:center; gap:10px; padding:10px; border-bottom:1px solid #2a2a3e;}
  li:last-child { border-bottom:none;}
  .name { font-weight:600;}
  .url { color:#64748B; font-size:12px; word-break:break-all;}
  .grow { flex:1; min-width:0;}
  .badge { padding:2px 8px; border-radius:999px; font-size:11px;}
  .badge.on { background:#0F3D1F; color:#7CBF88;}
  .badge.off { background:#3D0F0F; color:#D49090;}
  .row { display:flex; gap:8px; align-items:center; flex-wrap:wrap;}
  .err { color:#F87171; font-size:13px; margin-top:8px; min-height:18px;}
  .ok { color:#10B981;}
  .site-check { display:flex; align-items:center; gap:10px; padding:10px;
                background:#0D0D15; border-radius:8px; margin-bottom:6px;
                cursor:pointer; border:2px solid transparent;}
  .site-check input { width:18px; height:18px; cursor:pointer;}
  .site-check.checked { border-color:#3B82F6; background:#1A1F3A;}
  .hero { text-align:center; padding: 12px 0 4px;}
  .hero h2 { font-size: 17px; font-weight:600; margin: 0 0 6px; color:#fff;}
  .hero p { font-size: 13px; color:#94A3B8; margin: 0;}
  .history-head { display:flex; align-items:center; justify-content:space-between;
                  margin-top:10px;}
  .history-label { font-size:12px; color:#94A3B8;}
  .history-head button { margin-top:0;}
  .history-row { display:flex; flex-wrap:wrap; gap:6px; margin-top:6px;}
  .history-pill { background:#2a2a3e; color:#CBD5E1; padding:6px 10px;
                  border-radius:999px; font-size:12px; cursor:pointer;
                  border:none; margin:0;}
  .history-pill:hover { background:#3B82F6; color:#fff;}
  .steps { margin: 0 0 12px; padding: 0 0 0 22px; color:#CBD5E1; font-size:13px;
           line-height: 1.7;}
  .steps li { margin-bottom: 4px;}
  .steps code { background:#0D0D15; padding:1px 6px; border-radius:4px;
                font-size:12px; color:#93C5FD;}
  .link-btn { display:inline-block; background:#10B981; color:#fff; padding:10px 16px;
              border-radius:10px; text-decoration:none; font-weight:500; font-size:14px;
              margin-top:8px;}
  .link-btn:hover { background:#34D399;}
  .scan-list { list-style:none; padding:0; margin:8px 0 12px;}
  .scan-list li { display:flex; align-items:flex-start; gap:10px; padding:10px;
                  border-bottom:1px solid #2a2a3e;}
  .scan-list li:last-child { border-bottom:none;}
  .scan-list li.fail { opacity:0.55;}
  .scan-list input[type=checkbox] { width:18px; height:18px; cursor:pointer; margin-top:3px;}
  .scan-list .err-msg { color:#F87171; font-size:11px; margin-top:2px;}
</style>
</head>
<body>
<h1>🍿 咔滋影院 · 遠端遙控</h1>
<div class="tabs">
  <button class="tab active" data-tab="search">📱 遠端搜尋</button>
  <button class="tab" data-tab="import">📥 匯入站點</button>
  <button class="tab" data-tab="sites">⚙️ 站點管理</button>
</div>

<div id="panel-search" class="panel active">
  <div class="hero">
    <h2>在手機打字，讓 TV 搜尋</h2>
    <p>輸入關鍵字，選擇要搜的站點，送出就會在電視上打開搜尋結果</p>
  </div>
  <div class="card">
    <label>關鍵字</label>
    <div style="display:flex; gap:8px; align-items:stretch;">
      <input id="kw" type="text" placeholder="例如：慶餘年" autocomplete="off" autocapitalize="off" style="flex:1">
      <button class="secondary small" onclick="toSimp()" title="繁體轉簡體"
              style="margin-top:0; padding:0 14px; font-weight:bold;">簡</button>
    </div>
    <div class="history-head" id="kwHistoryHead" style="display:none">
      <span class="history-label">最近搜尋</span>
      <button class="secondary small" onclick="clearHistory()">清空</button>
    </div>
    <div class="history-row" id="kwHistory"></div>
    <!-- 送出 button 移到上面，站點多的時候不用滾到最底 -->
    <div class="row" style="margin-top:14px">
      <button onclick="submitSearch()" id="submitBtn" style="flex:1">🚀 送到 TV 執行搜尋</button>
    </div>
    <div id="searchMsg" class="err"></div>
    <div class="row" style="margin-top:14px; align-items:center">
      <label style="margin:0; flex:1">搜尋站點</label>
      <button class="secondary small" onclick="selAll(true)">全選</button>
      <button class="secondary small" onclick="selAll(false)">全不選</button>
    </div>
    <div id="siteChecks"></div>
  </div>
</div>

<div id="panel-import" class="panel">
  <div class="card">
    <h2 style="margin:0 0 10px; font-size:16px">怎麼用</h2>
    <ol class="steps">
      <li>在手機 app 開「站點管理」→ 批次操作 →「<strong>匯出站點到剪貼簿</strong>」</li>
      <li>切回這個瀏覽器分頁（剪貼簿的 JSON 已經在你手機裡）</li>
      <li>在下面方框<strong>長按 → 貼上</strong></li>
      <li>按「📥 匯入」→ 預覽要新增哪些（已存在的站會自動略過）</li>
      <li>確認沒問題就按「確定匯入」</li>
    </ol>
    <p style="margin:8px 0 0; color:#94A3B8; font-size:12px">
      格式：JSON array，例如
      <code>[{"name":"範例","url":"https://...","ssl_verify":true,"enabled":true}]</code>
    </p>
  </div>

  <div class="card">
    <label>貼匯出字串</label>
    <textarea id="importText" rows="8" placeholder="從手機 app 匯出的 JSON 貼這裡⋯"></textarea>
    <button onclick="runImportPreview()" id="importBtn" style="width:100%">📥 預覽匯入</button>
    <div id="importMsg" class="err"></div>
  </div>

  <div class="card" id="importPreviewCard" style="display:none">
    <h2 id="importPreviewTitle" style="margin:0 0 8px; font-size:16px;">預覽</h2>
    <ul id="importList" class="scan-list"></ul>
    <button onclick="confirmImport()" id="importConfirmBtn" style="width:100%">確定匯入</button>
    <div id="importDoneMsg" class="err"></div>
  </div>
</div>

<div id="panel-sites" class="panel">
  <div class="card">
    <label>站點 URL</label>
    <input id="url" type="text" placeholder="https://example.com" autocapitalize="off">
    <label>名稱（可留空自動生成）</label>
    <input id="name" type="text" placeholder="選填">
    <button onclick="addSite()">新增站點</button>
    <div id="addMsg" class="err"></div>
  </div>
  <div class="card">
    <h2 style="margin:0 0 10px; font-size:16px">站點清單</h2>
    <ul id="list"></ul>
  </div>
</div>

<script>
document.querySelectorAll('.tab').forEach(t => {
  t.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(x => x.classList.remove('active'));
    document.querySelectorAll('.panel').forEach(x => x.classList.remove('active'));
    t.classList.add('active');
    document.getElementById('panel-' + t.dataset.tab).classList.add('active');
  });
});

const recentKw = JSON.parse(localStorage.getItem('maccms_recent_kw') || '[]');

async function api(path, opts){
  const r = await fetch(path, opts);
  const t = await r.text();
  try { return { ok: r.ok, data: t ? JSON.parse(t) : null }; }
  catch { return { ok: r.ok, data: null, raw: t }; }
}

async function loadSites(){
  const r = await api('/api/sites');
  if (!r.ok) return;
  renderList(r.data);
  renderChecks(r.data);
}

function renderChecks(sites){
  const wrap = document.getElementById('siteChecks');
  wrap.innerHTML = '';
  sites.filter(s => s.enabled).forEach(s => {
    const id = 'chk-' + s.id;
    const div = document.createElement('label');
    div.className = 'site-check checked';
    div.innerHTML = '<input type="checkbox" id="'+id+'" value="'+s.id+'" checked onchange="toggleCheck(this)"><span>'+s.name+'</span>';
    wrap.appendChild(div);
  });
}
function toggleCheck(el){
  el.parentElement.classList.toggle('checked', el.checked);
}
function selAll(v){
  document.querySelectorAll('#siteChecks input').forEach(c => {
    c.checked = v;
    toggleCheck(c);
  });
}

function renderHistory(){
  const wrap = document.getElementById('kwHistory');
  const head = document.getElementById('kwHistoryHead');
  wrap.innerHTML = '';
  head.style.display = recentKw.length ? 'flex' : 'none';
  recentKw.slice(0, 10).forEach(k => {
    const b = document.createElement('button');
    b.className = 'history-pill';
    b.textContent = k;
    b.onclick = () => { document.getElementById('kw').value = k; };
    wrap.appendChild(b);
  });
}

function clearHistory(){
  if (!confirm('清空所有搜尋歷史？')) return;
  recentKw.length = 0;
  localStorage.setItem('maccms_recent_kw', JSON.stringify(recentKw));
  renderHistory();
}

function renderList(sites){
  const ul = document.getElementById('list');
  ul.innerHTML = '';
  sites.forEach(s => {
    const li = document.createElement('li');
    const onoff = s.enabled ? '<span class="badge on">啟用</span>' : '<span class="badge off">停用</span>';
    li.innerHTML = `
      <div class="grow">
        <div class="name">`+s.name+` `+onoff+`</div>
        <div class="url">`+s.url+`</div>
      </div>
      <button class="small secondary" onclick="move(`+s.id+`,'up')">▲</button>
      <button class="small secondary" onclick="move(`+s.id+`,'down')">▼</button>
      <button class="small" onclick="toggle(`+s.id+`, `+!s.enabled+`)">`+(s.enabled?'停用':'啟用')+`</button>
      <button class="small danger" onclick="del(`+s.id+`)">刪</button>
    `;
    ul.appendChild(li);
  });
}

async function toSimp(){
  const kwEl = document.getElementById('kw');
  const val = kwEl.value.trim();
  if (!val) return;
  const r = await api('/api/t2s', {method:'POST', headers:{'Content-Type':'application/json'},
                                    body: JSON.stringify({ text: val })});
  if (r.ok && r.data && typeof r.data.text === 'string') {
    kwEl.value = r.data.text;
  }
}

async function submitSearch(){
  const kw = document.getElementById('kw').value.trim();
  const msg = document.getElementById('searchMsg');
  msg.className = 'err'; msg.textContent = '';
  if (!kw) { msg.textContent = '請輸入關鍵字'; return; }
  const ids = [...document.querySelectorAll('#siteChecks input:checked')].map(x => parseInt(x.value));
  if (ids.length === 0) { msg.textContent = '請至少選一個站點'; return; }
  const btn = document.getElementById('submitBtn');
  btn.disabled = true; btn.textContent = '送出中...';
  const r = await api('/api/remote_search', {
    method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({ keyword: kw, site_ids: ids })
  });
  btn.disabled = false; btn.textContent = '🚀 送到 TV 執行搜尋';
  if (r.ok && r.data && r.data.status === 'success') {
    msg.className = 'err ok';
    msg.textContent = '✅ 已送出！請看 TV 畫面';
    const idx = recentKw.indexOf(kw);
    if (idx >= 0) recentKw.splice(idx, 1);
    recentKw.unshift(kw);
    if (recentKw.length > 10) recentKw.length = 10;
    localStorage.setItem('maccms_recent_kw', JSON.stringify(recentKw));
    renderHistory();
  } else {
    msg.textContent = (r.data && r.data.message) || '送出失敗';
  }
}
async function addSite(){
  const url = document.getElementById('url').value.trim();
  const name = document.getElementById('name').value.trim();
  const msg = document.getElementById('addMsg');
  msg.className = 'err'; msg.textContent = '';
  if (!url) { msg.textContent = '請輸入 URL'; return; }
  const r = await api('/api/sites', {method:'POST', headers:{'Content-Type':'application/json'},
                                      body: JSON.stringify({ url, name })});
  if (r.ok) {
    document.getElementById('url').value = '';
    document.getElementById('name').value = '';
    msg.className = 'err ok'; msg.textContent = '新增成功';
    setTimeout(()=>{msg.textContent=''; msg.className='err';}, 2000);
    loadSites();
  } else {
    msg.textContent = (r.data && r.data.message) || '新增失敗';
  }
}
async function del(id){
  if (!confirm('確定刪除？')) return;
  await api('/api/sites/' + id, { method:'DELETE' });
  loadSites();
}
async function toggle(id, enabled){
  await api('/api/sites/' + id, { method:'PUT', headers:{'Content-Type':'application/json'},
                                   body: JSON.stringify({ enabled })});
  loadSites();
}
async function move(id, direction){
  await api('/api/sites/' + id + '/move', { method:'POST', headers:{'Content-Type':'application/json'},
                                              body: JSON.stringify({ direction })});
  loadSites();
}

let importParsedRaw = null;
let importPreviewData = null;

async function runImportPreview(){
  const text = document.getElementById('importText').value;
  const msg = document.getElementById('importMsg');
  msg.className = 'err'; msg.textContent = '';
  if (!text.trim()) { msg.textContent = '請先貼東西進來'; return; }
  // 純客戶端先 parse 一次驗 JSON 格式 → 出錯不浪費 round-trip
  let parsed;
  try { parsed = JSON.parse(text); }
  catch (e) { msg.textContent = 'JSON 格式錯誤：' + e.message; return; }
  if (!Array.isArray(parsed)) { msg.textContent = '需要 JSON array（[ ... ]）'; return; }
  importParsedRaw = text;
  importPreviewData = parsed;
  renderImportPreview();
  msg.className = 'err ok';
  msg.textContent = '解析到 ' + parsed.length + ' 個站台，按下方「確定匯入」送出';
  document.getElementById('importPreviewCard').style.display = 'block';
}

function renderImportPreview(){
  const ul = document.getElementById('importList');
  ul.innerHTML = '';
  document.getElementById('importPreviewTitle').textContent = '預覽（' + importPreviewData.length + ' 個）';
  importPreviewData.forEach(function(item){
    const li = document.createElement('li');
    const name = item.name || '(未命名)';
    const url = item.url || '';
    li.innerHTML =
      '<div class="grow">' +
        '<div class="name">' + escapeHtml(name) + '</div>' +
        '<div class="url">' + escapeHtml(url) + '</div>' +
      '</div>';
    ul.appendChild(li);
  });
}

async function confirmImport(){
  const btn = document.getElementById('importConfirmBtn');
  const msg = document.getElementById('importDoneMsg');
  msg.className = 'err'; msg.textContent = '';
  if (!importParsedRaw) return;
  btn.disabled = true; btn.textContent = '匯入中⋯';
  try {
    const r = await api('/api/sites/import', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ text: importParsedRaw }),
    });
    if (!r.ok) {
      msg.textContent = (r.data && r.data.message) || '匯入失敗';
      return;
    }
    const added = r.data.added || 0;
    const failed = r.data.failed || 0;
    const skipped = r.data.skipped || 0;
    msg.className = 'err ok';
    msg.textContent = '已新增 ' + added + ' 個' +
      (skipped > 0 ? '（略過 ' + skipped + ' 個重複）' : '') +
      (failed > 0 ? '（失敗 ' + failed + ' 個）' : '');
    document.getElementById('importText').value = '';
    document.getElementById('importPreviewCard').style.display = 'none';
    importParsedRaw = null;
    importPreviewData = null;
    loadSites(); // 順便重整站點管理頁
  } finally {
    btn.disabled = false; btn.textContent = '確定匯入';
  }
}

function escapeHtml(s){
  return String(s).replace(/[&<>"']/g, function(ch){
    return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[ch];
  });
}

loadSites();
renderHistory();
</script>
</body>
</html>
""".trimIndent()
    }
}
