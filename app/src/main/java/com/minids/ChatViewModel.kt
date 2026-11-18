package com.minids

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minids.data.AppSettings
import com.minids.data.Attachment
import com.minids.data.Chat
import com.minids.data.ChatRepo
import com.minids.data.Msg
import com.minids.data.PrefsStore
import com.minids.net.ApiException
import com.minids.net.DeepSeekApi
import com.minids.net.Ev
import com.minids.util.AttachmentUtil
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
    val balanceError: String? = null
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PrefsStore(app)
    private val repo = ChatRepo(app)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UiState(chat = repo.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var startedAt = 0L

    init {
        viewModelScope.launch { prefs.migrateLegacy() }
        viewModelScope.launch {
            prefs.flow.collect { s -> _state.update { it.copy(settings = s) } }
        }
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
        viewModelScope.launch { prefs.setThinking(v) }
    }

    fun saveSearch(v: Boolean) {
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

    fun setDraft(v: String) = _state.update { it.copy(draft = v) }

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

    private fun runTurn(history: List<Msg>) {
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

        job = viewModelScope.launch {
            try {
                DeepSeekApi.chat(st.settings.apiKey, trimmed, st.settings.thinking, st.settings.search)
                    .collect { ev ->
                        when (ev) {
                            is Ev.Text -> {
                                text.append(ev.t)
                                patch(aid) { it.copy(text = text.toString(), searchStatus = null) }
                            }
                            is Ev.Think -> {
                                think.append(ev.t)
                                patch(aid) { it.copy(reasoning = think.toString()) }
                            }
                            is Ev.Status -> patch(aid) { it.copy(searchStatus = ev.s) }
                            is Ev.Meta -> patch(aid) { it.copy(toolCalls = ev.toolCalls, sources = ev.sources) }
                            is Ev.Usage -> _state.update {
                                it.copy(usage = formatUsage(ev))
                            }
                        }
                    }
                patch(aid) {
                    it.copy(
                        thinkingMs = now() - startedAt,
                        searchStatus = null
                    )
                }
            } catch (e: CancellationException) {
                patch(aid) { it.copy(thinkingMs = now() - startedAt, searchStatus = null) }
            } catch (e: Exception) {
                val msg = (e as? ApiException)?.message ?: e.message ?: "请求失败"
                patch(aid) { it.copy(error = msg, searchStatus = null) }
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
        val st = _state.value
        if (st.streaming || st.compressing) return
        if (st.settings.apiKey.isBlank()) {
            notice("先到设置里填写 API Key")
            return
        }

        val msgs = st.chat.messages
        val userIds = msgs.withIndex().filter { it.value.role == "user" }.map { it.index }
        if (userIds.size <= KEEP_ROUNDS) {
            notice("不足 $KEEP_ROUNDS 轮，不需要压缩")
            return
        }
        val cut = userIds[userIds.size - KEEP_ROUNDS]
        val head = msgs.subList(0, cut)
        val tail = msgs.subList(cut, msgs.size)
        if (head.isEmpty()) {
            notice("没有可压缩的历史")
            return
        }

        _state.update { it.copy(compressing = true) }
        viewModelScope.launch {
            try {
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
            } catch (e: Exception) {
                notice((e as? ApiException)?.message ?: "压缩失败：${e.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(compressing = false) }
            }
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

    /** 删除不再被任何消息引用的图片，避免历史/压缩后留下垃圾。 */
    private fun cleanupImages() {
        val keep = _state.value.chat.messages
            .flatMap { it.attachments }
            .mapNotNull { it.localPath }
            .toSet()
        runCatching {
            File(getApplication<Application>().filesDir, "images")
                .listFiles()
                ?.forEach { f -> if (f.absolutePath !in keep) f.delete() }
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
