package com.minids.net

import android.content.ContentResolver
import android.net.Uri
import com.minids.data.Attachment
import com.minids.data.Msg
import com.minids.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import com.minids.util.TextCodec
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, override val message: String) : IOException(message)

data class Balance(val currency: String, val total: String, val available: Boolean)

sealed interface Ev {
    data class Think(val t: String) : Ev
    data class Text(val t: String) : Ev
    data class Status(val s: String) : Ev
    data class Meta(val toolCalls: String?, val sources: List<Source>) : Ev
    data class Usage(val prompt: Int, val completion: Int, val cacheHit: Int) : Ev
}

object DeepSeekApi {

    const val MODEL = "deepseek-flash"
    private const val BASE = "https://api.deepseek.com"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /* ---------------- 文件上传 ---------------- */

    suspend fun uploadImage(key: String, jpeg: ByteArray, name: String): String =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("purpose", "user_data")
                .addFormDataPart("file", name, jpeg.toRequestBody("image/jpeg".toMediaType()))
                .build()
            postFile(key, multipart)
        }

    suspend fun uploadBytes(
        key: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        purpose: String
    ): String = withContext(Dispatchers.IO) {
        val isText = mime.startsWith("text/", true)
        // 文本一律按 UTF-8 上传，并在开头加 BOM：
        // 服务端对“ASCII 开头 + 少量中文”的 txt 会误判成本地编码，加 BOM 就不会认错。
        val body = if (isText) withUtf8Bom(bytes) else bytes
        val type = if (isText && !mime.contains("charset", true)) "$mime; charset=utf-8" else mime
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose", purpose)
            .addFormDataPart("file", name, body.toRequestBody(type.toMediaTypeOrNull()))
            .build()
        postFile(key, multipart)
    }

    private fun withUtf8Bom(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return bytes
        }
        val out = ByteArray(bytes.size + 3)
        out[0] = 0xEF.toByte()
        out[1] = 0xBB.toByte()
        out[2] = 0xBF.toByte()
        bytes.copyInto(out, 3)
        return out
    }

    suspend fun uploadStream(
        key: String,
        uri: Uri,
        resolver: ContentResolver,
        name: String,
        mime: String,
        size: Long,
        purpose: String
    ): String = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri)
                    ?: throw ApiException(-1, "无法读取这个文件")
                input.use { sink.writeAll(it.source()) }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose", purpose)
            .addFormDataPart("file", name, body)
            .build()
        postFile(key, multipart)
    }

    private fun postFile(key: String, body: MultipartBody): String {
        val req = Request.Builder()
            .url("$BASE/files")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        uploadClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, friendly(resp.code, text))
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            return obj?.get("id")?.jsonPrimitive?.contentOrNull
                ?: throw ApiException(resp.code, "上传失败")
        }
    }

    /* ---------------- 余额 ---------------- */

    suspend fun balance(key: String): Balance = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/user/balance")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, friendly(resp.code, text))
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: throw ApiException(-1, "余额返回格式异常")
            val info = (root["balance_infos"] as? JsonArray)?.firstOrNull() as? JsonObject
            Balance(
                currency = info?.str("currency") ?: "CNY",
                total = info?.str("total_balance") ?: "0.00",
                available = (root["is_available"] as? JsonPrimitive)?.content?.toBoolean() ?: true
            )
        }
    }

    /* ---------------- 模型信息 ---------------- */

    /** GET /models 返回的 name，即官方展示名（如 DeepSeek-V4.1-Flash）。 */
    suspend fun modelName(key: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val text = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return@use null
            val list = root["data"] as? JsonArray ?: return@use null
            list.mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("id") == MODEL }
                ?.str("name")
        }
    }

    /* ---------------- 对话流 ---------------- */

    fun chat(
        key: String,
        messages: List<Msg>,
        thinking: Boolean,
        search: Boolean
    ): Flow<Ev> = callbackFlow {
        val inflight = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)

        val job = launch(Dispatchers.IO) {
            try {
                // 文本附件要读本地文件，放到 IO 线程里拼请求体
                val payload = buildBody(messages, thinking, search)
                val req = Request.Builder()
                    .url("$BASE/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .header("Accept", "text/event-stream")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                val call = client.newCall(req)
                inflight.set(call)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        close(ApiException(resp.code, friendly(resp.code, body)))
                        return@use
                    }
                    val source = resp.body?.source()
                        ?: throw ApiException(-1, "服务器没有返回内容")
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        try {
                            parse(data).forEach { send(it) }
                        } catch (e: ApiException) {
                            throw e
                        } catch (_: Exception) {
                            // 单个分片解析失败就跳过，不打断整条流
                        }
                    }
                    close()
                }
            } catch (e: ApiException) {
                close(e)
            } catch (e: Exception) {
                if (!isClosedForSend) close(ApiException(-1, e.message ?: "网络异常，请检查连接"))
            }
        }

        awaitClose {
            inflight.getAndSet(null)?.cancel()
            job.cancel()
        }
    }

    private fun buildBody(messages: List<Msg>, thinking: Boolean, search: Boolean): String {
        val root = buildJsonObject {
            put("model", MODEL)
            put("stream", true)
            put("max_tokens", if (thinking) 16384 else 8192)
            put("thinking", buildJsonObject {
                put("type", if (thinking) "enabled" else "disabled")
            })
            if (thinking) put("reasoning_effort", "low")
            put("stream_options", buildJsonObject { put("include_usage", true) })
            if (search) {
                put("tools", buildJsonArray {
                    addJsonObject { put("type", "deepseek_web_search") }
                    addJsonObject { put("type", "deepseek_read_url") }
                })
            }
            put("messages", buildJsonArray {
                val lastUser = messages.indexOfLast { it.role == "user" }
                messages.forEachIndexed { index, m ->
                    if (index == lastUser) {
                        addJsonObject {
                            put("role", "latest_reminder")
                            put("content", reminderLine())
                        }
                    }
                    add(messageJson(m, index == lastUser))
                }
            })
        }
        return root.toString()
    }

    /** 官方 User Metadata Marker：日期,星期,地点,平台,语言。 */
    private fun reminderLine(): String {
        val now = LocalDateTime.now()
        val weekday = when (now.dayOfWeek) {
            DayOfWeek.MONDAY -> "星期一"
            DayOfWeek.TUESDAY -> "星期二"
            DayOfWeek.WEDNESDAY -> "星期三"
            DayOfWeek.THURSDAY -> "星期四"
            DayOfWeek.FRIDAY -> "星期五"
            DayOfWeek.SATURDAY -> "星期六"
            DayOfWeek.SUNDAY -> "星期日"
        }
        return "${now.toLocalDate()},$weekday,,App,中文"
    }

    private fun messageJson(m: Msg, isLastUser: Boolean = false): JsonObject = buildJsonObject {
        put("role", m.role)
        val body = m.text.trim()

        if (m.role == "user" && m.attachments.isNotEmpty()) {
            put("content", buildJsonArray {
                if (body.isNotBlank()) {
                    addJsonObject {
                        put("type", "text")
                        put("text", body)
                    }
                }
                m.attachments.forEach { a ->
                    val inline = inlineText(a)
                    if (inline != null) {
                        // 直接把文本内容放进消息，不交给服务端猜编码：
                        // “1+1=6 换行 + 中文”的 txt 会被它误判成本地编码，读出来是乱码
                        addJsonObject {
                            put("type", "text")
                            put("text", "【附件 ${a.name}】\n$inline")
                        }
                    } else {
                        addJsonObject {
                            put("type", "file")
                            put("file_id", a.fileId)
                        }
                    }
                }
            })
        } else {
            put("content", body)
        }
        if (m.role == "assistant") {
            val tc = m.toolCalls
            if (!tc.isNullOrBlank()) {
                runCatching { json.parseToJsonElement(tc) }.getOrNull()?.let { put("server_tool_calls", it) }
            }
        }
    }

    /** 有本地副本的文本附件：直接内联进消息，最多 1 MiB。 */
    private fun inlineText(a: Attachment): String? = runCatching {
        if (a.isImage || !TextCodec.isTextName(a.name)) return@runCatching null
        val path = a.localPath ?: return@runCatching null
        val file = File(path)
        if (!file.isFile || file.length() > 1_048_576L) return@runCatching null
        val text = TextCodec.decode(file.readBytes())
        if (text.isBlank()) null else text
    }.getOrNull()

    private fun parse(raw: String): List<Ev> {
        val out = mutableListOf<Ev>()
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return out

        (root["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "请求失败"
            throw ApiException(-1, msg)
        }

        (root["usage"] as? JsonObject)?.let { u ->
            out += Ev.Usage(
                prompt = u.int("prompt_tokens"),
                completion = u.int("completion_tokens"),
                cacheHit = u.int("prompt_cache_hit_tokens")
            )
        }

        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return out

        (choice["server_tool_event"] as? JsonObject)?.let { ev ->
            val type = ev.str("type")
            val call = ev["call"] as? JsonObject
            if (type == "call_started" && call != null) {
                val action = call["action"] as? JsonObject
                val qs = (action?.get("queries") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val url = action?.str("url")
                val label = when {
                    !qs.isNullOrEmpty() -> "正在搜索：${qs.joinToString("、")}"
                    url != null -> "正在阅读：$url"
                    else -> "正在联网…"
                }
                out += Ev.Status(label)
            }
        }

        val delta = choice["delta"] as? JsonObject
        if (delta != null) {
            delta.str("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { out += Ev.Think(it) }
            delta.str("content")?.takeIf { it.isNotEmpty() }?.let { out += Ev.Text(it) }

            val tools = delta["server_tool_calls"] as? JsonArray
            if (tools != null && tools.isNotEmpty()) {
                out += Ev.Meta(tools.toString(), extractSources(tools))
            }
        }

        val finish = choice.str("finish_reason")
        if (finish == "content_filter") {
            throw ApiException(-1, "内容被审核拦截，换个问法试试")
        }
        return out
    }

    private fun extractSources(tools: JsonArray): List<Source> {
        val out = LinkedHashMap<String, Source>()
        tools.forEach { step ->
            val calls = (step as? JsonObject)?.get("calls") as? JsonArray ?: return@forEach
            calls.forEach { c ->
                val call = c as? JsonObject ?: return@forEach
                (call["sources"] as? JsonArray)?.forEach { s ->
                    val o = s as? JsonObject ?: return@forEach
                    val url = o.str("url") ?: return@forEach
                    if (!out.containsKey(url)) {
                        out[url] = Source(
                            title = o.str("title") ?: url,
                            url = url,
                            site = o.str("site_name")
                        )
                    }
                }
                (call["page"] as? JsonObject)?.let { p ->
                    val url = p.str("url") ?: return@let
                    if (!out.containsKey(url)) {
                        out[url] = Source(title = p.str("title") ?: url, url = url, site = null)
                    }
                }
            }
        }
        return out.values.toList()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun friendly(code: Int, body: String): String {
        val detail = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            (o["error"] as? JsonObject)?.str("message")
        }.getOrNull()

        val base = when (code) {
            400 -> "请求参数有误"
            401 -> "API Key 无效"
            402 -> "账户余额不足"
            403 -> "没有权限"
            408 -> "上传超时"
            413 -> "文件太大"
            422 -> "参数格式错误"
            429 -> "请求太频繁，稍后再试"
            500 -> "服务器内部错误"
            503 -> "服务繁忙，稍后再试"
            504 -> "文件解析超时"
            else -> if (code > 0) "请求失败（$code）" else "请求失败"
        }
        return if (detail.isNullOrBlank()) base else "$base：$detail"
    }
}
