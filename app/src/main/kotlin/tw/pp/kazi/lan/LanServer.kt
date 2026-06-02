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
    // 帳號同步:讀目前伺服器網址(預填面板)、存設定並測試登入(回傳是否成功)
    private val currentSyncUrl: () -> String = { "" },
    private val saveSync: suspend (String, String) -> Boolean = { _, _ -> false },
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
                uri == PATH_API_SYNC && method == Method.GET -> syncConfig()
                uri == PATH_API_SYNC && method == Method.POST -> saveSyncConfig(session)
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

    private fun syncConfig(): Response {
        val url = currentSyncUrl()
        return jsonResponse(Response.Status.OK, buildJsonObject {
            put("configured", url.isNotBlank())
            put("url", url)
        })
    }

    private fun saveSyncConfig(session: IHTTPSession): Response = runBlocking {
        val body = readBody(session)
        val obj = parseJsonObject(body) ?: return@runBlocking badRequest("Invalid JSON")
        val url = (obj["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val pw = (obj["password"] as? JsonPrimitive)?.content.orEmpty()
        if (url.isBlank() || pw.isBlank()) return@runBlocking badRequest("網址與密碼都要填")
        val ok = saveSync(url, pw)
        jsonResponse(if (ok) Response.Status.OK else Response.Status.BAD_REQUEST, buildJsonObject {
            put("status", if (ok) STATUS_SUCCESS else STATUS_ERROR)
            put("message", if (ok) "已連線,開始同步" else "登入失敗,請確認網址與密碼")
        })
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

    // LAN server 無認證、同網段誰都能打;body 上限擋掉「宣稱超大 Content-Length 一次配爆記憶體」的 OOM
    private val maxBodyBytes = 4 * 1024 * 1024
    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (contentLength <= 0) return ""
        if (contentLength > maxBodyBytes) return ""
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
        private const val PATH_API_SYNC = "/api/sync"
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
  input[type=text], input[type=password], textarea {
    width:100%; padding:12px 14px; background:#0D0D15; color:#fff;
    border:2px solid #2a2a3e; border-radius:10px; font-size:15px; font-family:inherit;
    box-sizing:border-box;}
  input[type=text]:focus, input[type=password]:focus, textarea:focus { outline:none; border-color:#3B82F6;}
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
  /* history-pill 內分兩個 button：text 點觸發搜尋、del 點刪除這筆。整顆 pill 用 inline-flex 不分行 */
  .history-pill { display:inline-flex; align-items:stretch; background:#2a2a3e;
                  border-radius:999px; overflow:hidden; font-size:12px;}
  .history-pill button { background:transparent; color:#CBD5E1;
                          border:none; cursor:pointer; margin:0; font-size:12px;
                          font-family:inherit; line-height:1;}
  .history-pill .pill-text { padding:7px 4px 7px 12px;}
  .history-pill .pill-del { padding:7px 10px 7px 4px; color:#64748B; font-size:11px;}
  .history-pill .pill-text:hover { background:#3B82F6; color:#fff;}
  .history-pill .pill-del:hover { background:#3D0F0F; color:#F87171;}
  /* input 內嵌 ✕ 清空按鈕：input 有值時 (.has-value) 才顯示 */
  .input-with-clear { position:relative; flex:1;}
  .input-with-clear input { padding-right:38px; width:100%;}
  .input-with-clear .clear-btn {
    position:absolute; right:6px; top:50%; transform:translateY(-50%);
    background:transparent; color:#94A3B8; border:none; padding:6px 10px;
    font-size:16px; cursor:pointer; margin:0; line-height:1; display:none;}
  .input-with-clear .clear-btn:hover { color:#fff; background:transparent;}
  .input-with-clear.has-value .clear-btn { display:block;}
  /* 兩段式確認：armed 時按鈕變紅，3 秒沒第二次點就 revert */
  button.armed { background:#EF4444 !important;}
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
  /* 匯入頁：步驟卡美化 */
  .steps-title { font-size:13px; font-weight:600; color:#94A3B8; letter-spacing:.5px;
                 margin-bottom:10px; text-transform:uppercase;}
  .steps { counter-reset: step; list-style:none; padding:0; margin:0;}
  .steps li { position:relative; padding:6px 0 6px 34px; margin:0; border:none;
              color:#CBD5E1; font-size:14px; line-height:1.6; display:block;}
  .steps li::before { counter-increment: step; content: counter(step);
              position:absolute; left:0; top:6px; width:22px; height:22px;
              background:#3B82F6; color:#fff; border-radius:50%; font-size:12px;
              font-weight:600; display:flex; align-items:center; justify-content:center;}
  .steps code { background:#0D0D15; padding:1px 6px; border-radius:4px; font-size:12px; color:#93C5FD;}
  .fmt-note { margin:12px 0 0; color:#64748B; font-size:12px; line-height:1.5;}
  /* 彈出式 modal */
  .modal-overlay { position:fixed; inset:0; background:rgba(0,0,0,.65);
    display:none; align-items:center; justify-content:center; padding:16px; z-index:100;}
  .modal-overlay.open { display:flex; animation: fadeIn .15s ease;}
  @keyframes fadeIn { from { opacity:0;} to { opacity:1;} }
  .modal { background:#1E1E2E; border:1px solid #2a2a3e; border-radius:16px;
    width:100%; max-width:520px; max-height:86vh; display:flex; flex-direction:column;
    box-shadow:0 24px 70px rgba(0,0,0,.55);}
  .modal-head { display:flex; align-items:center; gap:8px; padding:16px 18px;
    border-bottom:1px solid #2a2a3e;}
  .modal-head h2 { margin:0; font-size:16px; flex:1;}
  .modal-close { background:transparent; color:#94A3B8; border:none; font-size:20px;
    cursor:pointer; margin:0; padding:2px 8px; line-height:1;}
  .modal-close:hover { color:#fff; background:transparent;}
  .modal-actions { display:flex; align-items:center; gap:8px; padding:12px 18px 4px;}
  .modal-actions .sel-count { margin-left:auto; font-size:12px; color:#94A3B8;}
  .modal-body { overflow-y:auto; padding:6px 18px 8px; margin:0; flex:1;}
  .modal-foot { display:flex; gap:10px; padding:14px 18px; border-top:1px solid #2a2a3e;}
  .modal-foot button { margin-top:0;}
  .scan-list li label.grow { cursor:pointer;}
</style>
</head>
<body>
<h1>🍿 咔滋影院 · 遠端遙控</h1>
<div class="tabs">
  <button class="tab active" data-tab="search">📱 遠端搜尋</button>
  <button class="tab" data-tab="import">📥 匯入站點</button>
  <button class="tab" data-tab="sites">⚙️ 站點管理</button>
  <button class="tab" data-tab="sync">🔄 同步</button>
</div>

<div id="panel-search" class="panel active">
  <div class="hero">
    <h2>在手機打字，讓 TV 搜尋</h2>
    <p>輸入關鍵字，選擇要搜的站點，送出就會在電視上打開搜尋結果</p>
  </div>
  <div class="card">
    <label>關鍵字</label>
    <div style="display:flex; gap:8px; align-items:stretch;">
      <div class="input-with-clear">
        <input id="kw" type="text" placeholder="例如：片名" autocomplete="off" autocapitalize="off"
               oninput="updateClearBtn(this)" onkeydown="if(event.key==='Enter')submitSearch()">
        <button class="clear-btn" onclick="clearInput('kw')" title="清空" tabindex="-1">✕</button>
      </div>
      <button class="secondary small" onclick="toSimp()" title="繁體轉簡體"
              style="margin-top:0; padding:0 14px; font-weight:bold;">簡</button>
    </div>
    <div class="history-head" id="kwHistoryHead" style="display:none">
      <span class="history-label">最近搜尋（單筆按 ✕ 刪除）</span>
      <button class="secondary small" onclick="clearHistory(this)">清空</button>
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
  <div class="hero">
    <h2>從手機把站點搬過來</h2>
    <p>手機 app 匯出清單 → 貼上 → 勾選要加入的站台</p>
  </div>
  <div class="card">
    <div class="steps-title">怎麼用</div>
    <ol class="steps">
      <li>手機 app 開「站點管理」→ 批次操作 →「<strong>匯出站點到剪貼簿</strong>」</li>
      <li>切回這個瀏覽器分頁（JSON 已經在你手機剪貼簿裡）</li>
      <li>在下面方框<strong>長按 → 貼上</strong></li>
      <li>按「<strong>預覽要匯入哪些</strong>」→ 勾選想要的 → 確定匯入</li>
    </ol>
    <p class="fmt-note">格式：JSON array，例如<br>
      <code>[{"name":"範例","url":"https://...","ssl_verify":true,"enabled":true}]</code></p>
  </div>

  <div class="card">
    <label>貼上匯出字串</label>
    <textarea id="importText" rows="7" placeholder="從手機 app 匯出的 JSON 貼這裡⋯"></textarea>
    <button onclick="runImportPreview()" id="importBtn" style="width:100%">📥 預覽要匯入哪些</button>
    <div id="importMsg" class="err"></div>
  </div>
</div>

<div id="panel-sync" class="panel">
  <div class="hero">
    <h2>帳號同步設定</h2>
    <p>填你的網頁版網址 + 密碼,觀看歷史與收藏就會跟網頁、其他裝置同步(在這裡打字比用遙控器方便)</p>
  </div>
  <div class="card">
    <label>伺服器網址</label>
    <div class="input-with-clear">
      <input id="syncUrl" type="text" placeholder="https://你的網址.vercel.app" autocapitalize="off" autocomplete="off" oninput="updateClearBtn(this)">
      <button class="clear-btn" onclick="clearInput('syncUrl')" title="清空" tabindex="-1">✕</button>
    </div>
    <label style="margin-top:12px">密碼</label>
    <input id="syncPw" type="password" placeholder="網頁登入密碼" autocomplete="off">
    <button onclick="saveSync()" id="syncBtn" style="width:100%; margin-top:14px">💾 儲存並測試連線</button>
    <div id="syncMsg" class="err"></div>
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
    <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:10px;">
      <h2 style="margin:0; font-size:16px">站點清單</h2>
      <span id="siteCountLabel" style="font-size:12px; color:#94A3B8;"></span>
    </div>
    <div class="input-with-clear" style="margin-bottom:10px;">
      <input id="siteFilter" type="text" placeholder="搜尋站點名稱或 URL"
             autocomplete="off" oninput="updateClearBtn(this); renderList()">
      <button class="clear-btn" onclick="clearInput('siteFilter', renderList)" title="清空" tabindex="-1">✕</button>
    </div>
    <ul id="list"></ul>
  </div>
</div>

<!-- 匯入預覽：彈出式 modal（點背景或 ✕ 關閉） -->
<div id="importModal" class="modal-overlay" onclick="if(event.target===this)closeImportModal()">
  <div class="modal">
    <div class="modal-head">
      <h2 id="importPreviewTitle">預覽匯入</h2>
      <button class="modal-close" onclick="closeImportModal()" title="關閉">✕</button>
    </div>
    <div class="modal-actions">
      <button class="secondary small" style="margin-top:0" onclick="importSelAll(true)">全選</button>
      <button class="secondary small" style="margin-top:0" onclick="importSelAll(false)">全不選</button>
      <span class="sel-count" id="importSelCount"></span>
    </div>
    <ul id="importList" class="scan-list modal-body"></ul>
    <div class="modal-foot">
      <button class="secondary" onclick="closeImportModal()">取消</button>
      <button onclick="confirmImport()" id="importConfirmBtn" style="flex:2">確定匯入</button>
    </div>
    <div id="importDoneMsg" class="err" style="padding:0 18px 14px; margin-top:-4px"></div>
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
let lastSites = [];
loadSync();

async function loadSync(){
  const r = await api('/api/sync');
  if (r.ok && r.data && r.data.url) document.getElementById('syncUrl').value = r.data.url;
}
async function saveSync(){
  const url = document.getElementById('syncUrl').value.trim();
  const pw = document.getElementById('syncPw').value;
  const msg = document.getElementById('syncMsg');
  const btn = document.getElementById('syncBtn');
  if (!url || !pw){ msg.style.color='#f87171'; msg.textContent='網址與密碼都要填'; return; }
  btn.disabled = true; btn.textContent = '連線中…'; msg.textContent='';
  const r = await api('/api/sync', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({url:url, password:pw}) });
  btn.disabled = false; btn.textContent = '💾 儲存並測試連線';
  const ok = r.ok && r.data && r.data.status === 'success';
  msg.style.color = ok ? '#4ade80' : '#f87171';
  msg.textContent = (r.data && r.data.message) || (ok ? '已連線' : '失敗');
}

async function api(path, opts){
  const r = await fetch(path, opts);
  const t = await r.text();
  try { return { ok: r.ok, data: t ? JSON.parse(t) : null }; }
  catch { return { ok: r.ok, data: null, raw: t }; }
}

async function loadSites(){
  const r = await api('/api/sites');
  if (!r.ok) return;
  lastSites = r.data;
  renderList();
  renderChecks(r.data);
  // 預覽 modal 開著時，「新增/已存在」badge 跟著 site list 同步（沒開就別重繪，免得洗掉勾選）
  if (importPreviewData && isImportModalOpen()) renderImportPreview();
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
  recentKw.slice(0, 10).forEach((k, idx) => {
    const pill = document.createElement('span');
    pill.className = 'history-pill';
    const text = document.createElement('button');
    text.className = 'pill-text';
    text.textContent = k;
    text.title = '填入關鍵字';
    text.onclick = () => {
      const el = document.getElementById('kw');
      el.value = k;
      updateClearBtn(el);
      el.focus();
    };
    const delBtn = document.createElement('button');
    delBtn.className = 'pill-del';
    delBtn.textContent = '✕';
    delBtn.title = '刪除這筆';
    delBtn.onclick = (e) => { e.stopPropagation(); removeHistory(idx); };
    pill.appendChild(text);
    pill.appendChild(delBtn);
    wrap.appendChild(pill);
  });
}

function removeHistory(idx){
  if (idx < 0 || idx >= recentKw.length) return;
  recentKw.splice(idx, 1);
  localStorage.setItem('maccms_recent_kw', JSON.stringify(recentKw));
  renderHistory();
}

// 兩段式確認 helper：第一次點按鈕變紅 + 文字變確認，3 秒沒第二次點就 revert，第二次點才執行
function armConfirm(btn, originalLabel, confirmLabel, action, ms){
  if (ms === undefined) ms = 3000;
  if (btn.dataset.armed === '1') {
    if (btn._armTimer) clearTimeout(btn._armTimer);
    btn.dataset.armed = '0';
    btn.textContent = originalLabel;
    btn.classList.remove('armed');
    action();
    return;
  }
  btn.dataset.armed = '1';
  btn.dataset.originalLabel = originalLabel;
  btn.textContent = confirmLabel;
  btn.classList.add('armed');
  if (btn._armTimer) clearTimeout(btn._armTimer);
  btn._armTimer = setTimeout(() => {
    btn.dataset.armed = '0';
    btn.textContent = originalLabel;
    btn.classList.remove('armed');
  }, ms);
}

function clearHistory(btn){
  armConfirm(btn, '清空', '再按一次清空', () => {
    recentKw.length = 0;
    localStorage.setItem('maccms_recent_kw', JSON.stringify(recentKw));
    renderHistory();
  });
}

function updateClearBtn(el){
  el.parentElement.classList.toggle('has-value', el.value.length > 0);
}

function clearInput(id, postCb){
  const el = document.getElementById(id);
  el.value = '';
  updateClearBtn(el);
  el.focus();
  if (typeof postCb === 'function') postCb();
}

function renderList(){
  const ul = document.getElementById('list');
  ul.innerHTML = '';
  const filterEl = document.getElementById('siteFilter');
  const filter = filterEl ? filterEl.value.trim().toLowerCase() : '';
  const sites = filter
    ? lastSites.filter(s =>
        (s.name || '').toLowerCase().includes(filter) ||
        (s.url || '').toLowerCase().includes(filter))
    : lastSites;
  const label = document.getElementById('siteCountLabel');
  if (label) {
    label.textContent = filter
      ? sites.length + ' / ' + lastSites.length + ' 個'
      : lastSites.length + ' 個';
  }
  if (sites.length === 0) {
    const li = document.createElement('li');
    li.style.justifyContent = 'center';
    li.style.color = '#64748B';
    li.textContent = filter ? '沒有符合的站點' : '還沒有站點';
    ul.appendChild(li);
    return;
  }
  sites.forEach((s, displayIdx) => {
    const realIdx = lastSites.indexOf(s);
    const atTop = realIdx === 0;
    const atBottom = realIdx === lastSites.length - 1;
    const li = document.createElement('li');
    const onoff = s.enabled ? '<span class="badge on">啟用</span>' : '<span class="badge off">停用</span>';
    // ⤒ 移到最頂 / ⤓ 移到最底 — 比 ▲▼ 單格移動快、按一次到位
    li.innerHTML = `
      <div class="grow">
        <div class="name">`+escapeHtml(s.name)+` `+onoff+`</div>
        <div class="url">`+escapeHtml(s.url)+`</div>
      </div>
      <button class="small secondary" onclick="moveToTop(`+s.id+`)" `+(atTop?'disabled':'')+` title="移到最頂">⤒</button>
      <button class="small secondary" onclick="moveToBottom(`+s.id+`)" `+(atBottom?'disabled':'')+` title="移到最底">⤓</button>
      <button class="small" onclick="toggle(`+s.id+`, `+!s.enabled+`)">`+(s.enabled?'停用':'啟用')+`</button>
      <button class="small danger" onclick="del(`+s.id+`, this)">刪</button>
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
    updateClearBtn(kwEl);
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
    // 送出成功後清空關鍵字 — 方便連續搜尋下一個，且讓 ✕ 跟「最近搜尋」pill 同時可見不擋路。
    // 不主動 focus 避免手機自動彈鍵盤干擾使用者看 TV
    const kwEl = document.getElementById('kw');
    kwEl.value = '';
    updateClearBtn(kwEl);
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
async function del(id, btn){
  armConfirm(btn, '刪', '確定？', async () => {
    await api('/api/sites/' + id, { method:'DELETE' });
    loadSites();
  });
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

// 連續呼叫單格 move 直到 id 在頂端 / 底端。比一次性 reorder API 慢但不必改 server side。
// 在 LAN 上 round-trip 通常 <30ms，10 個站點 < 300ms。期間禁用相關按鈕避免重複觸發
async function moveToTop(id){
  const startIdx = lastSites.findIndex(s => s.id === id);
  if (startIdx <= 0) return;
  for (let i = 0; i < startIdx; i++) {
    await api('/api/sites/' + id + '/move', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ direction: 'up' }),
    });
  }
  loadSites();
}

async function moveToBottom(id){
  const startIdx = lastSites.findIndex(s => s.id === id);
  if (startIdx < 0 || startIdx === lastSites.length - 1) return;
  const steps = lastSites.length - 1 - startIdx;
  for (let i = 0; i < steps; i++) {
    await api('/api/sites/' + id + '/move', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ direction: 'down' }),
    });
  }
  loadSites();
}

let importPreviewData = null;

function openImportModal(){ document.getElementById('importModal').classList.add('open'); }
function closeImportModal(){ document.getElementById('importModal').classList.remove('open'); }
function isImportModalOpen(){ return document.getElementById('importModal').classList.contains('open'); }

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
  if (parsed.length === 0) { msg.textContent = '清單是空的'; return; }
  importPreviewData = parsed;
  document.getElementById('importDoneMsg').textContent = '';
  renderImportPreview();
  openImportModal();
}

function renderImportPreview(){
  const ul = document.getElementById('importList');
  ul.innerHTML = '';
  // 用現有站點 URL（normalized 後比對）標記「新增 / 已存在」。已存在的會被 server 略過、不覆蓋既有設定
  const existing = new Set(lastSites.map(s => (s.url || '').trim().toLowerCase()));
  let newCount = 0, dupCount = 0;
  importPreviewData.forEach(function(item, idx){
    const name = item.name || '(未命名)';
    const url = item.url || '';
    const isDup = existing.has(url.trim().toLowerCase());
    if (isDup) dupCount++; else newCount++;
    const badge = isDup
      ? '<span class="badge off" style="margin-left:6px">已存在</span>'
      : '<span class="badge on" style="margin-left:6px">新增</span>';
    const li = document.createElement('li');
    if (isDup) li.className = 'fail';
    // 新增的預設勾選；已存在的預設不勾且 disabled（送了 server 也會略過）
    li.innerHTML =
      '<input type="checkbox" data-idx="' + idx + '" ' + (isDup ? 'disabled' : 'checked') +
        ' onchange="updateImportCount()">' +
      '<label class="grow">' +
        '<div class="name">' + escapeHtml(name) + badge + '</div>' +
        '<div class="url">' + escapeHtml(url) + '</div>' +
      '</label>';
    ul.appendChild(li);
  });
  document.getElementById('importPreviewTitle').textContent =
    '預覽匯入（共 ' + importPreviewData.length + ' 個 · 新增 ' + newCount + ' 個 · 已存在 ' + dupCount + ' 個）';
  updateImportCount();
}

function updateImportCount(){
  const checked = document.querySelectorAll('#importList input[type=checkbox]:checked').length;
  const btn = document.getElementById('importConfirmBtn');
  btn.textContent = checked > 0 ? ('確定匯入（' + checked + ' 個）') : '確定匯入';
  btn.disabled = checked === 0;
  const label = document.getElementById('importSelCount');
  if (label) label.textContent = '已選 ' + checked + ' 個';
}

function importSelAll(v){
  document.querySelectorAll('#importList input[type=checkbox]:not(:disabled)').forEach(function(c){ c.checked = v; });
  updateImportCount();
}

async function confirmImport(){
  const btn = document.getElementById('importConfirmBtn');
  const dmsg = document.getElementById('importDoneMsg');
  dmsg.className = 'err'; dmsg.textContent = '';
  if (!importPreviewData) return;
  const idxs = [...document.querySelectorAll('#importList input[type=checkbox]:checked')]
    .map(function(c){ return parseInt(c.dataset.idx, 10); });
  if (idxs.length === 0) return;
  // 只送勾選的那幾筆；server 的匯入端吃 JSON array，所以篩完重新 stringify 即可（不必改 server）
  const selected = idxs.map(function(i){ return importPreviewData[i]; });
  btn.disabled = true; btn.textContent = '匯入中⋯';
  try {
    const r = await api('/api/sites/import', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ text: JSON.stringify(selected) }),
    });
    if (!r.ok) {
      dmsg.textContent = (r.data && r.data.message) || '匯入失敗';
      return;
    }
    const added = r.data.added || 0;
    const failed = r.data.failed || 0;
    const skipped = r.data.skipped || 0;
    closeImportModal();
    importPreviewData = null;
    document.getElementById('importText').value = '';
    const msg = document.getElementById('importMsg');
    msg.className = 'err ok';
    msg.textContent = '✅ 已新增 ' + added + ' 個' +
      (skipped > 0 ? '（略過 ' + skipped + ' 個已存在）' : '') +
      (failed > 0 ? '（失敗 ' + failed + ' 個）' : '');
    loadSites(); // 順便重整站點管理頁
  } finally {
    btn.disabled = false; updateImportCount();
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
