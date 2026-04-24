package tw.pp.kazi.lan

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

    private val lock = Mutex()

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
                uri == PATH_API_REMOTE_SEARCH && method == Method.POST -> remoteSearch(session)
                uri == PATH_API_T2S && method == Method.POST -> t2s(session)
                uri.startsWith(PATH_API_SITE_PREFIX) && uri.endsWith(PATH_MOVE_SUFFIX) -> moveSite(session)
                uri.startsWith(PATH_API_SITE_PREFIX) && method == Method.DELETE -> deleteSite(session)
                uri.startsWith(PATH_API_SITE_PREFIX) && method == Method.PUT -> updateSite(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
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
        val result = lock.withLock { siteRepository.addSite(url, name) }
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
        lock.withLock { siteRepository.updateSite(updated) }
        jsonResponse(Response.Status.OK, buildJsonObject { put("status", STATUS_SUCCESS) })
    }

    private fun deleteSite(session: IHTTPSession): Response = runBlocking {
        val id = session.uri.removePrefix(PATH_API_SITE_PREFIX).toLongOrNull()
            ?: return@runBlocking badRequest("Invalid id")
        lock.withLock { siteRepository.deleteSite(id) }
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
        lock.withLock { siteRepository.moveSite(id, direction) }
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
<title>咔滋影院 · LAN 控制</title>
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
  .history-row { display:flex; flex-wrap:wrap; gap:6px; margin-top:10px;}
  .history-pill { background:#2a2a3e; color:#CBD5E1; padding:6px 10px;
                  border-radius:999px; font-size:12px; cursor:pointer;
                  border:none; margin:0;}
  .history-pill:hover { background:#3B82F6; color:#fff;}
</style>
</head>
<body>
<h1>🍿 咔滋影院 · LAN 控制</h1>
<div class="tabs">
  <button class="tab active" data-tab="search">📱 遠端搜尋</button>
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
    <div class="history-row" id="kwHistory"></div>
    <label style="margin-top:14px">搜尋站點</label>
    <div id="siteChecks"></div>
    <div class="row">
      <button class="secondary small" onclick="selAll(true)">全選</button>
      <button class="secondary small" onclick="selAll(false)">全不選</button>
    </div>
    <div class="row" style="margin-top:12px">
      <button onclick="submitSearch()" id="submitBtn" style="flex:1">🚀 送到 TV 執行搜尋</button>
    </div>
    <div id="searchMsg" class="err"></div>
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
    div.innerHTML = `<input type="checkbox" id="`+id+`" value="`+s.id+`" checked onchange="toggleCheck(this)"><span>`+s.name+`</span>`;
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
  wrap.innerHTML = '';
  recentKw.slice(0, 10).forEach(k => {
    const b = document.createElement('button');
    b.className = 'history-pill';
    b.textContent = k;
    b.onclick = () => { document.getElementById('kw').value = k; };
    wrap.appendChild(b);
  });
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
loadSites();
renderHistory();
</script>
</body>
</html>
""".trimIndent()
    }
}
