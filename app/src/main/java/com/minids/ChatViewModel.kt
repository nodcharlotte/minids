package com.minids

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minids.data.AppSettings
import com.minids.data.Attachment
import com.minids.data.Chat
import com.minids.data.ChatRepo
import com.minids.data.Msg
import com.minids.data.PrefsStore
import com.minids.data.Source
import com.minids.net.ApiException
import com.minids.net.DeepSeekApi
import com.minids.net.Ev
import com.minids.net.WebBridge
import com.minids.net.WebEvents
import com.minids.net.WebFiles
import com.minids.net.WebServer
import com.minids.net.WebAtt
import com.minids.net.WebSink
import com.minids.util.AttachmentUtil
import com.minids.util.Lan
import com.minids.util.TextCodec
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.UUID

data class UiState(
    val chat: Chat = Chat(),
    val settings: AppSettings = AppSettings(),
    val draft: String = "",
    val pending: List<Attachment> = emptyList(),
    val streaming: Boolean = false,
    val uploading: Boolean = false,
    val compressing: Boolean = false,
    val usage: String = "",
    val notice: String? = null,
    val editingId: String? = null,
    val editingText: String = "",
    val editingAttachments: List<Attachment> = emptyList(),
    val balance: String? = null,
    val balanceLoading: Boolean = false,
    val balanceError: String? = null,
    val webOn: Boolean = false,
    val webUrl: String? = null
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PrefsStore(app)
    private val repo = ChatRepo(app)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UiState(chat = repo.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val json = Json { encodeDefaults = true }
    private var server: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var localSeq = 0

    private var job: Job? = null
    private var startedAt = 0L

    init {
        viewModelScope.launch { prefs.migrateLegacy() }
        viewModelScope.launch {
            prefs.flow.collect { s -> _state.update { it.copy(settings = s) } }
        }
        WebBridge.send = { text, thinking, search, atts, editId, sink ->
            webSend(text, thinking, search, atts, editId, sink)
        }
        WebBridge.upload = { name, mime, bytes -> webUpload(name, mime, bytes) }
        WebBridge.saveLocal = { name, mime, bytes -> webSaveLocal(name, mime, bytes) }
        WebBridge.compress = { runBlocking { runCompress() } }
        WebBridge.snapshot = {
            val st = _state.value
            buildJsonObject {
                put("messages", json.encodeToJsonElement(ListSerializer(Msg.serializer()), st.chat.messages))
                put("usage", st.usage)
                put("model", st.settings.modelName ?: "deepseek-flash")
            }.toString()
        }
        WebBridge.imagePath = { id ->
            _state.value.chat.messages
                .flatMap { it.attachments }
                .firstOrNull { it.fileId == id }
                ?.localPath
        }
        WebBridge.getSettings = {
            val s = _state.value.settings
            buildJsonObject {
                put("thinking", s.thinking)
                put("search", s.search)
                put("rev", WebEvents.current())
            }.toString()
        }
        WebBridge.setSettings = { thinking, search ->
            if (thinking != null) saveThinking(thinking)
            if (search != null) saveSearch(search)
        }
        WebBridge.streaming = { _state.value.streaming }
        WebBridge.stop = { stop() }
    }

    override fun onCleared() {
        stopWeb()
        super.onCleared()
    }

    /* ---------------- 设置 ---------------- */

    fun saveApiKey(v: String) {
        viewModelScope.launch {
            prefs.setApiKey(v)
            // 这里必须用刚保存的 Key，不能读 state：DataStore 的 flow 还没回流
            fetchModelName(v)
        }
    }

    /** 只在“更换但留空关闭”时调用，用当前已保存的 Key 重拉。 */
    fun refreshModelName() {
        fetchModelName(_state.value.settings.apiKey)
    }

    private fun fetchModelName(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            val name = runCatching { DeepSeekApi.modelName(key) }.getOrNull()
            if (!name.isNullOrBlank()) prefs.setModelName(name)
        }
    }

    fun saveContextTokens(v: Int) {
        viewModelScope.launch { prefs.setContextTokens(v) }
    }

    fun saveThinking(v: Boolean) {
        _state.update { it.copy(settings = it.settings.copy(thinking = v)) }
        WebEvents.bump()
        viewModelScope.launch { prefs.setThinking(v) }
    }

    fun saveSearch(v: Boolean) {
        _state.update { it.copy(settings = it.settings.copy(search = v)) }
        WebEvents.bump()
        viewModelScope.launch { prefs.setSearch(v) }
    }

    fun saveDark(v: Boolean) {
        viewModelScope.launch { prefs.setDark(v) }
    }

    fun loadBalance() {
        val key = _state.value.settings.apiKey
        if (key.isBlank()) {
            _state.update {
                it.copy(balance = null, balanceError = "未填写 API Key", balanceLoading = false)
            }
            return
        }
        _state.update { it.copy(balanceLoading = true, balanceError = null) }
        viewModelScope.launch {
            try {
                val b = DeepSeekApi.balance(key)
                val symbol = if (b.currency == "USD") "$" else "￥"
                _state.update {
                    it.copy(balance = "$symbol${b.total}", balanceLoading = false, balanceError = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        balanceLoading = false,
                        balanceError = (e as? ApiException)?.message ?: "余额查询失败"
                    )
                }
            }
        }
    }

    /* ---------------- 输入 ---------------- */

    fun setDraft(v: String) {
        val current = _state.value.draft
        // 一次粘贴进来一大段文本，自动转成 txt 附件（与网页端一致）
        if (v.length - current.length > 1500) {
            attachTextAsTxt(v)
            return
        }
        _state.update { it.copy(draft = v) }
    }

    /** 长文本转 txt：上传到 Files API，并留一份本地副本。 */
    fun attachTextAsTxt(text: String) {
        val st = _state.value
        if (st.settings.apiKey.isBlank()) {
            _state.update { it.copy(draft = text) }
            notice("先到设置里填写 API Key")
            return
        }
        _state.update { it.copy(uploading = true) }
        viewModelScope.launch {
            try {
                val name = "text-${System.currentTimeMillis()}.txt"
                val bytes = text.toByteArray(Charsets.UTF_8)
                val id = DeepSeekApi.uploadBytes(st.settings.apiKey, bytes, name, "text/plain", "user_data")
                val local = saveUpload(bytes, name, "text/plain")
                if (local != null) WebFiles.put(id, local)
                _state.update {
                    it.copy(pending = it.pending + Attachment(id, name, false, local))
                }
            } catch (e: Exception) {
                notice((e as? ApiException)?.message ?: "上传失败：${e.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(uploading = false) }
            }
        }
    }

    /**
     * 编辑中的 txt 被改写：先落本地，立刻替换附件（保存按钮马上结束）。
     * 真正的上传推迟到发送时，避免保存一次 txt 要等好几秒。
     */
    fun replaceEditAttachment(oldFileId: String, text: String, onDone: (Boolean) -> Unit) {
        val name = "text-${System.currentTimeMillis()}.txt"
        val local = saveUpload(TextCodec.utf8(text), name, "text/plain")
        if (local == null) {
            notice("保存失败")
            onDone(false)
            return
        }
        val token = newLocalToken()
        WebFiles.put(token, local)
        val fresh = Attachment(fileId = token, name = name, isImage = false, localPath = local)
        _state.update { s ->
            s.copy(
                editingAttachments = s.editingAttachments.map {
                    if (it.fileId == oldFileId) fresh else it
                }
            )
        }
        onDone(true)
    }

    private fun newLocalToken(): String = synchronized(this) {
        localSeq++
        "local-${System.currentTimeMillis()}-$localSeq"
    }

    fun removePending(fileId: String) =
        _state.update { s -> s.copy(pending = s.pending.filterNot { it.fileId == fileId }) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    private fun notice(msg: String) = _state.update { it.copy(notice = msg) }

    /* ---------------- 附件 ---------------- */

    fun attach(uri: Uri, isImage: Boolean) {
        val st = _state.value
        if (st.uploading) return
        if (st.settings.apiKey.isBlank()) {
            notice("先到设置里填写 API Key")
            return
        }
        _state.update { it.copy(uploading = true) }
        viewModelScope.launch {
            try {
                val key = st.settings.apiKey
                val att = if (isImage) {
                    AttachmentUtil.pickImage(getApplication(), uri, key)
                } else {
                    AttachmentUtil.pickFile(getApplication(), uri, key)
                }
                _state.update { it.copy(pending = it.pending + att) }
            } catch (e: Exception) {
                notice((e as? ApiException)?.message ?: "上传失败：${e.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(uploading = false) }
            }
        }
    }

    /* ---------------- 发送 ---------------- */

    fun send() {
        val st = _state.value
        if (st.streaming || st.uploading || st.compressing) return
        if (st.editingId != null) return
        val text = st.draft.trim()
        if (text.isEmpty() && st.pending.isEmpty()) return
        if (st.settings.apiKey.isBlank()) {
            notice("先到设置里填写 API Key")
            return
        }

        // 编辑过的 txt 还只是本地文件，发送前补上传
        if (st.pending.any { pendingUpload(it) }) {
            _state.update { it.copy(uploading = true) }
            viewModelScope.launch {
                val result = runCatching { uploadLocals(st.pending) }
                _state.update { s ->
                    s.copy(uploading = false, pending = result.getOrDefault(s.pending))
                }
                result
                    .onSuccess { send() }
                    .onFailure { notice((it as? ApiException)?.message ?: "上传失败：${it.message ?: "未知错误"}") }
            }
            return
        }

        val user = Msg(
            id = uid(),
            role = "user",
            text = text,
            attachments = st.pending,
            ts = now()
        )
        val history = st.chat.messages + user
        _state.update {
            it.copy(
                chat = it.chat.copy(messages = history, updatedAt = now()),
                draft = "",
                pending = emptyList()
            )
        }
        runTurn(history)
    }

    /** 附件是否还没进 DS Files（编辑后只存了本地）。 */
    private fun pendingUpload(a: Attachment): Boolean =
        a.fileId.isBlank() || a.fileId.startsWith("local-")

    private suspend fun uploadLocals(list: List<Attachment>): List<Attachment> {
        if (list.none { pendingUpload(it) }) return list
        val key = _state.value.settings.apiKey
        if (key.isBlank()) throw ApiException(-1, "先填写 API Key")
        return list.map { a ->
            if (!pendingUpload(a)) a else {
                val path = a.localPath ?: throw ApiException(-1, "${a.name} 已丢失，请重新添加")
                val file = File(path)
                if (!file.exists()) throw ApiException(-1, "${a.name} 已丢失，请重新添加")
                val mime = if (a.isImage) "image/jpeg" else "text/plain"
                val id = DeepSeekApi.uploadBytes(key, file.readBytes(), a.name, mime, "user_data")
                WebFiles.put(id, path)
                a.copy(fileId = id)
            }
        }
    }

    fun runTurn(
        history: List<Msg>,
        thinking: Boolean = _state.value.settings.thinking,
        search: Boolean = _state.value.settings.search,
        sink: WebSink? = null
    ) {
        val st = _state.value
        val trimmed = trimToBudget(history, st.settings.contextTokens)
        val aid = uid()
        startedAt = now()

        _state.update {
            it.copy(
                chat = it.chat.copy(messages = it.chat.messages + Msg(id = aid, role = "assistant", ts = startedAt)),
                streaming = true,
                usage = ""
            )
        }

        val text = StringBuilder()
        val think = StringBuilder()

        // 不在主线程收集：手机端一边排版长回复一边收 token 时会把主线程堵住，
        // 网页端的事件也跟着一卡一大段。放到 Default，网页按 token 实时收。
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                DeepSeekApi.chat(st.settings.apiKey, trimmed, thinking, search)
                    .collect { ev ->
                        when (ev) {
                            is Ev.Text -> {
                                text.append(ev.t)
                                patch(aid) { it.copy(text = text.toString(), searchStatus = null) }
                                sink?.text(ev.t)
                            }
                            is Ev.Think -> {
                                think.append(ev.t)
                                patch(aid) { it.copy(reasoning = think.toString()) }
                                sink?.think(ev.t)
                            }
                            is Ev.Status -> {
                                patch(aid) { it.copy(searchStatus = ev.s) }
                                sink?.status(ev.s)
                            }
                            is Ev.Meta -> {
                                patch(aid) { it.copy(toolCalls = ev.toolCalls, sources = ev.sources) }
                                if (ev.sources.isNotEmpty()) {
                                    sink?.sources(json.encodeToString(ListSerializer(Source.serializer()), ev.sources))
                                }
                            }
                            is Ev.Usage -> {
                                val formatted = formatUsage(ev)
                                _state.update { it.copy(usage = formatted) }
                                sink?.usage(formatted)
                            }
                        }
                    }
                patch(aid) {
                    it.copy(
                        thinkingMs = now() - startedAt,
                        searchStatus = null
                    )
                }
                sink?.finish(null)
            } catch (e: CancellationException) {
                patch(aid) { it.copy(thinkingMs = now() - startedAt, searchStatus = null) }
                sink?.finish("已停止")
            } catch (e: Exception) {
                val msg = (e as? ApiException)?.message ?: e.message ?: "请求失败"
                patch(aid) { it.copy(error = msg, searchStatus = null) }
                sink?.finish(msg)
            } finally {
                _state.update { it.copy(streaming = false) }
                dropEmptyAssistant()
                persist()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(streaming = false) }
        persist()
    }

    /* ---------------- 电脑访问 ---------------- */

    fun setWeb(enabled: Boolean) {
        if (!enabled) {
            stopWeb()
            return
        }
        if (_state.value.settings.apiKey.isBlank()) {
            notice("先填写 API Key")
            return
        }
        if (server != null) return
        val s = WebServer(getApplication<Application>().assets, 8080)
        try {
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            runCatching { s.stop() }
            _state.update { st -> st.copy(webOn = false, webUrl = null) }
            notice("启动失败：${e.message ?: "端口 8080 可能被占用"}")
            return
        }
        server = s
        holdLocks()
        val ip = Lan.ipv4() ?: "127.0.0.1"
        val url = "http://$ip:8080"
        _state.update { it.copy(webOn = true, webUrl = url) }
        runCatching { WebService.start(getApplication(), url) }.onFailure {
            notice("前台服务没起来，息屏后可能卡顿：${it.message ?: "未知错误"}")
        }
    }

    private fun stopWeb() {
        runCatching { server?.stop() }
        server = null
        releaseLocks()
        runCatching { WebService.stop(getApplication()) }
        _state.update { it.copy(webOn = false, webUrl = null) }
    }

    /**
     * 电脑端在用、手机屏幕却关着时，系统会让进程休眠：流式会攒成一大段一大段，
     * 上传也要等好几分钟。开着电脑访问期间拿住 CPU 和 Wi-Fi。
     */
    private fun holdLocks() {
        runCatching {
            val app = getApplication<Application>()
            if (wakeLock == null) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiniDS:web").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null) {
                val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MiniDS:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun webSend(
        text: String,
        thinking: Boolean,
        search: Boolean,
        atts: List<WebAtt>,
        editId: String?,
        sink: WebSink
    ) {
        val st = _state.value
        if (st.settings.apiKey.isBlank()) {
            sink.finish("未填写 API Key")
            return
        }
        if (st.streaming) {
            sink.finish("正在生成中，请稍候")
            return
        }
        val attachments = runBlocking(Dispatchers.IO) {
            runCatching {
                atts.map {
                    val id = if (it.fileId.isBlank() || it.fileId.startsWith("local-")) {
                        val path = WebFiles.get(it.fileId)
                            ?: throw ApiException(-1, "${it.name} 已丢失，请重新上传")
                        val mime = if (it.isImage) "image/jpeg" else "text/plain"
                        DeepSeekApi.uploadBytes(st.settings.apiKey, File(path).readBytes(), it.name, mime, "user_data")
                            .also { real -> WebFiles.put(real, path) }
                    } else {
                        it.fileId
                    }
                    Attachment(
                        fileId = id,
                        name = it.name,
                        isImage = it.isImage,
                        localPath = it.localPath
                    )
                }
            }
        }.getOrElse {
            sink.finish((it as? ApiException)?.message ?: "上传失败：${it.message ?: "未知错误"}")
            return
        }

        val history = if (editId != null) {
            val index = st.chat.messages.indexOfFirst { it.id == editId }
            if (index < 0) {
                sink.finish("这条消息已经不在了")
                return
            }
            val original = st.chat.messages[index]
            st.chat.messages.take(index) + original.copy(text = text, attachments = attachments)
        } else {
            st.chat.messages + Msg(
                id = uid(),
                role = "user",
                text = text,
                attachments = attachments,
                ts = now()
            )
        }

        _state.update {
            it.copy(chat = it.chat.copy(messages = history, updatedAt = now()))
        }
        if (editId != null) cleanupImages()
        runTurn(history, thinking, search, sink)
    }

    private fun webUpload(name: String, mime: String, bytes: ByteArray): String {
        val key = _state.value.settings.apiKey
        if (key.isBlank()) return """{"error":"未填写 API Key"}"""
        return runBlocking(Dispatchers.IO) {
            runCatching {
                val isImage = mime.startsWith("image/")
                // 记事本存的 GBK/ANSI 文本先转成 UTF-8，模型读到的才不是乱码
                val body = if (TextCodec.isText(mime, name)) TextCodec.utf8(TextCodec.decode(bytes)) else bytes
                val lower = name.lowercase()
                val needsParse = listOf(".pdf", ".doc", ".docx", ".ppt", ".pptx").any { lower.endsWith(it) }
                val purpose = if (needsParse) "text_recognition" else "user_data"
                val id = DeepSeekApi.uploadBytes(key, body, name, mime, purpose)
                val local = saveUpload(body, name, mime)
                if (local != null) WebFiles.put(id, local)
                buildJsonObject {
                    put("fileId", id)
                    put("name", name)
                    put("isImage", isImage)
                    if (local != null) put("localPath", local)
                }.toString()
            }.getOrElse {
                """{"error":"${(it.message ?: "上传失败").replace("\"", "'")}"}"""
            }
        }
    }

    /** 只写本地，返回一个 local- 令牌；发送时再传给 DS。 */
    private fun webSaveLocal(name: String, mime: String, bytes: ByteArray): String {
        val body = if (TextCodec.isText(mime, name)) TextCodec.utf8(TextCodec.decode(bytes)) else bytes
        val local = saveUpload(body, name, mime) ?: return """{"error":"保存失败"}"""
        val token = newLocalToken()
        WebFiles.put(token, local)
        return buildJsonObject {
            put("fileId", token)
            put("name", name)
            put("isImage", mime.startsWith("image/"))
            put("localPath", local)
        }.toString()
    }

    /* ---------------- 编辑重发 ---------------- */

    fun startEdit(id: String) {
        val st = _state.value
        if (st.streaming || st.uploading || st.compressing) return
        val m = st.chat.messages.firstOrNull { it.id == id } ?: return
        if (m.role != "user") return
        _state.update {
            it.copy(editingId = id, editingText = m.text, editingAttachments = m.attachments)
        }
    }

    fun setEditText(v: String) = _state.update { it.copy(editingText = v) }

    fun cancelEdit() = _state.update {
        it.copy(editingId = null, editingText = "", editingAttachments = emptyList())
    }

    fun removeEditAttachment(fileId: String) = _state.update { s ->
        s.copy(editingAttachments = s.editingAttachments.filterNot { it.fileId == fileId })
    }

    fun attachToEdit(uri: Uri) {
        val st = _state.value
        if (st.uploading) return
        if (st.settings.apiKey.isBlank()) {
            notice("先到设置里填写 API Key")
            return
        }
        _state.update { it.copy(uploading = true) }
        viewModelScope.launch {
            try {
                val a = AttachmentUtil.pickImage(getApplication(), uri, st.settings.apiKey)
                _state.update { it.copy(editingAttachments = it.editingAttachments + a) }
            } catch (e: Exception) {
                notice((e as? ApiException)?.message ?: "上传失败：${e.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(uploading = false) }
            }
        }
    }

    fun submitEdit() {
        val st = _state.value
        if (st.streaming) return
        val id = st.editingId ?: return
        val text = st.editingText.trim()
        if (text.isEmpty() && st.editingAttachments.isEmpty()) {
            notice("内容不能为空")
            return
        }
        val index = st.chat.messages.indexOfFirst { it.id == id }
        if (index < 0) return

        if (st.editingAttachments.any { pendingUpload(it) }) {
            _state.update { it.copy(uploading = true) }
            viewModelScope.launch {
                val result = runCatching { uploadLocals(st.editingAttachments) }
                _state.update { s ->
                    s.copy(uploading = false, editingAttachments = result.getOrDefault(s.editingAttachments))
                }
                result
                    .onSuccess { submitEdit() }
                    .onFailure { notice((it as? ApiException)?.message ?: "上传失败：${it.message ?: "未知错误"}") }
            }
            return
        }

        val edited = st.chat.messages[index].copy(text = text, attachments = st.editingAttachments)
        val truncated = st.chat.messages.take(index) + edited

        _state.update {
            it.copy(
                chat = it.chat.copy(messages = truncated, updatedAt = now()),
                editingId = null,
                editingText = "",
                editingAttachments = emptyList()
            )
        }
        runTurn(truncated)
        cleanupImages()
    }

    /* ---------------- 新建 / 压缩 ---------------- */

    fun newChat() {
        if (_state.value.streaming) return
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                chat = Chat(),
                draft = "",
                pending = emptyList(),
                usage = "",
                notice = null,
                streaming = false,
                editingId = null,
                editingText = "",
                editingAttachments = emptyList()
            )
        }
        repo.clear()
        cleanupImages()
    }

    fun compress() {
        if (_state.value.streaming || _state.value.compressing) return
        viewModelScope.launch { runCompress() }
    }

    private suspend fun runCompress(): Boolean {
        val st = _state.value
        if (st.streaming || st.compressing) return false
        if (st.settings.apiKey.isBlank()) {
            notice("先到设置里填写 API Key")
            return false
        }

        val msgs = st.chat.messages
        val userIds = msgs.withIndex().filter { it.value.role == "user" }.map { it.index }
        if (userIds.size <= KEEP_ROUNDS) {
            notice("不足 $KEEP_ROUNDS 轮，不需要压缩")
            return false
        }
        val cut = userIds[userIds.size - KEEP_ROUNDS]
        val head = msgs.subList(0, cut)
        val tail = msgs.subList(cut, msgs.size)
        if (head.isEmpty()) {
            notice("没有可压缩的历史")
            return false
        }

        _state.update { it.copy(compressing = true) }
        return try {
            val prompt = buildString {
                append("把下面这段对话压缩成摘要，保留关键事实、结论、决定、用户偏好和未完成事项。")
                append("用中文，最多 600 字，只输出摘要正文，不要开场白。\n\n")
                head.forEach { m ->
                    if (m.text.isBlank()) return@forEach
                    val who = when (m.role) {
                        "user" -> "用户"
                        "assistant" -> "助手"
                        else -> "备注"
                    }
                    append(who).append("：").append(m.text).append("\n\n")
                }
            }
            val sb = StringBuilder()
            DeepSeekApi.chat(
                st.settings.apiKey,
                listOf(Msg(id = uid(), role = "user", text = prompt)),
                thinking = false,
                search = false
            ).collect { if (it is Ev.Text) sb.append(it.t) }

            val body = sb.toString().trim()
            if (body.isEmpty()) throw IllegalStateException("模型没有返回摘要")

            val summary = Msg(
                id = uid(),
                role = "system",
                text = "【早前对话摘要】\n$body",
                ts = now()
            )
            _state.update {
                it.copy(chat = Chat(messages = listOf(summary) + tail, updatedAt = now()))
            }
            persist()
            cleanupImages()
            notice("已压缩，保留最近 $KEEP_ROUNDS 轮")
            true
        } catch (e: Exception) {
            notice((e as? ApiException)?.message ?: "压缩失败：${e.message ?: "未知错误"}")
            false
        } finally {
            _state.update { it.copy(compressing = false) }
        }
    }

    /* ---------------- 内部工具 ---------------- */

    private fun patch(id: String, block: (Msg) -> Msg) {
        _state.update { s ->
            s.copy(chat = s.chat.copy(messages = s.chat.messages.map { if (it.id == id) block(it) else it }))
        }
    }

    private fun dropEmptyAssistant() {
        _state.update { s ->
            val list = s.chat.messages
            val last = list.lastOrNull()
            if (last != null && last.role == "assistant" && last.text.isEmpty() &&
                last.reasoning.isEmpty() && last.error == null
            ) {
                s.copy(chat = s.chat.copy(messages = list.dropLast(1)))
            } else {
                s
            }
        }
    }

    private fun persist() {
        val chat = _state.value.chat
        io.launch { repo.save(chat) }
    }

    /** 网页上传的文件在手机留一份：图片可直接显示，文本可在网页里再次编辑。 */
    private fun saveUpload(bytes: ByteArray, name: String, mime: String): String? = runCatching {
        val ext = when {
            name.contains('.') -> name.substringAfterLast('.').lowercase().take(8)
            mime.contains("png", true) -> "png"
            mime.contains("jpeg", true) -> "jpg"
            mime.contains("webp", true) -> "webp"
            mime.contains("gif", true) -> "gif"
            mime.startsWith("text/") -> "txt"
            else -> "bin"
        }
        val dir = File(getApplication<Application>().filesDir, "uploads").apply { mkdirs() }
        val file = File(dir, "u_${System.currentTimeMillis()}.$ext")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    /** 删除不再被任何消息引用的图片/附件，避免历史/压缩后留下垃圾。 */
    private fun cleanupImages() {
        val keep = (
            _state.value.chat.messages.flatMap { it.attachments } +
                _state.value.editingAttachments +
                _state.value.pending
            )
            .mapNotNull { it.localPath }
            .toSet()
        runCatching {
            val base = getApplication<Application>().filesDir
            listOf(File(base, "images"), File(base, "uploads")).forEach { dir ->
                dir.listFiles()?.forEach { f -> if (f.absolutePath !in keep) f.delete() }
            }
        }
    }

    /**
     * 按预算从尾部取整段，并把起点对齐到 user 消息，
     * 避免裁剪后请求以 assistant 开头（API 会 400）。
     */
    private fun trimToBudget(all: List<Msg>, budget: Int): List<Msg> {
        val normalized = normalize(all)
        if (normalized.isEmpty()) return normalized

        val head = normalized.takeWhile { it.role == "system" }
        val body = normalized.drop(head.size)
        if (body.isEmpty() || budget <= 0) return normalized

        var total = 0
        var start = body.size
        for (i in body.indices.reversed()) {
            val c = cost(body[i])
            if (total + c > budget && start < body.size) break
            total += c
            start = i
        }
        while (start < body.size && body[start].role != "user") start++
        val tail = if (start < body.size) body.drop(start) else body.takeLast(1)
        return head + tail
    }

    /** 去掉失败的空气泡，合并相邻 user 消息，保持 user/assistant 交替。 */
    private fun normalize(messages: List<Msg>): List<Msg> {
        val out = mutableListOf<Msg>()
        for (m in messages) {
            if (m.role == "assistant" && m.text.isBlank() && m.toolCalls.isNullOrBlank()) continue
            val last = out.lastOrNull()
            if (m.role == "user" && last != null && last.role == "user") {
                out[out.size - 1] = last.copy(
                    text = listOf(last.text, m.text).filter { it.isNotBlank() }.joinToString("\n\n"),
                    attachments = last.attachments + m.attachments
                )
            } else {
                out += m
            }
        }
        return out
    }

    private fun cost(m: Msg): Int {
        var c = estimate(m.text)
        m.attachments.forEach { a ->
            c += if (a.isImage) 1024 else 3000
        }
        m.sources.forEach { c += 80 }
        return c
    }

    private fun estimate(s: String): Int {
        if (s.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in s) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF) {
                cjk++
            } else {
                other++
            }
        }
        return (cjk * 0.9f + other * 0.3f).toInt() + 4
    }

    private fun formatUsage(u: Ev.Usage): String {
        val pct = if (u.prompt > 0) Math.round(u.cacheHit * 100f / u.prompt) else 0
        return "输入 ${compact(u.prompt)} token · 缓存命中 $pct% · 输出 ${compact(u.completion)} token"
    }

    private fun compact(v: Int): String =
        if (v >= 1000) String.format(Locale.US, "%.1fk", v / 1000f) else v.toString()

    private fun uid() = UUID.randomUUID().toString().take(8)

    private fun now() = System.currentTimeMillis()

    companion object {
        const val KEEP_ROUNDS = 10
    }
}
