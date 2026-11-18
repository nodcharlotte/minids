package com.minids.net

import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import com.minids.util.TextCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder

interface WebSink {
    fun think(text: String)
    fun text(text: String)
    fun status(text: String)
    fun sources(json: String)
    fun usage(text: String)
    fun finish(error: String?)
}

data class WebAtt(
    val fileId: String,
    val name: String,
    val isImage: Boolean,
    val localPath: String? = null
)

/** 设置变更的版本号 + 长轮询唤醒，避免网页定时轮询。 */
object WebEvents {
    private val lock = Object()
    private var rev = 0

    fun current(): Int = synchronized(lock) { rev }

    fun bump() {
        synchronized(lock) {
            rev++
            lock.notifyAll()
        }
    }

    fun await(since: Int, timeoutMs: Long): Int = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (rev == since) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            try {
                lock.wait(left)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        rev
    }
}

/** 网页上传的文件先在内存登记，刚上传完还没进消息列表时也能取到。 */
object WebFiles {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun put(fileId: String, path: String) { map[fileId] = path }
    fun get(fileId: String): String? = map[fileId]
}

/** ViewModel 与 HTTP 服务器之间的桥。 */
object WebBridge {
    @Volatile var send: ((String, Boolean, Boolean, List<WebAtt>, String?, WebSink) -> Unit)? = null
    @Volatile var upload: ((name: String, mime: String, bytes: ByteArray) -> String)? = null
    /** 只落本地、不传 DS，发送时再补上传（编辑 txt 保存要立刻返回）。 */
    @Volatile var saveLocal: ((name: String, mime: String, bytes: ByteArray) -> String)? = null
    @Volatile var compress: (() -> Boolean)? = null
    @Volatile var imagePath: (String) -> String? = { null }
    @Volatile var snapshot: () -> String = { """{"messages":[]}""" }
    @Volatile var getSettings: () -> String = { """{"thinking":false,"search":false}""" }
    @Volatile var setSettings: ((Boolean?, Boolean?) -> Unit)? = null
    @Volatile var streaming: () -> Boolean = { false }
    @Volatile var stop: () -> Unit = {}
}

/**
 * 网页端事件缓冲。用长轮询而不是 SSE：
 * NanoHTTPD 的响应输出带缓冲，SSE 会被攒到最后一次性发出。
 * 轮询请求在服务端挂住，有新事件立刻返回，空闲时不产生请求。
 */
object WebStream {
    private val lock = Object()
    private val events = mutableListOf<String>()
    private var finished = false

    fun reset() {
        synchronized(lock) {
            events.clear()
            finished = false
        }
    }

    fun add(type: String, data: String) {
        val payload = buildJsonObject {
            put("type", type)
            put("data", data)
        }.toString()
        synchronized(lock) {
            events.add(payload)
            lock.notifyAll()
        }
    }

    fun finish(error: String?) {
        add("done", error ?: "")
        synchronized(lock) {
            finished = true
            lock.notifyAll()
        }
    }

    fun snapshot(from: Int): Triple<List<String>, Boolean, Int> = synchronized(lock) {
        snapshotLocked(from)
    }

    /**
     * 有事件就立刻返回；没有就最多等 timeoutMs。
     * 被唤醒后再多等 20ms，把同一批 token 合并成一个响应，避免每个 token 一次请求。
     */
    fun await(from: Int, timeoutMs: Long, coalesceMs: Long = 60): Triple<List<String>, Boolean, Int> =
        synchronized(lock) {
            if (from >= events.size && !finished) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (from >= events.size && !finished) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
            }
            if (from < events.size && coalesceMs > 0) {
                // 同一批 token 合并：60ms 内到的事件一起发，减少请求数
                val batchUntil = System.currentTimeMillis() + coalesceMs
                while (true) {
                    val left = batchUntil - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
            }
            snapshotLocked(from)
        }

    private fun snapshotLocked(from: Int): Triple<List<String>, Boolean, Int> {
        val list = if (from in 0 until events.size) events.subList(from, events.size).toList() else emptyList()
        return Triple(list, finished, events.size)
    }
}

class WebServer(
    private val assets: AssetManager,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/", "/index.html" -> page()
                "/api/state" -> fixed(WebBridge.snapshot(), "application/json")
                "/api/settings" ->
                    if (session.method == Method.POST) setSettings(session)
                    else fixed(WebBridge.getSettings(), "application/json")
                "/api/wait" -> {
                    val since = session.parameters["rev"]?.firstOrNull()?.toIntOrNull() ?: 0
                    WebEvents.await(since, 25_000)
                    fixed(WebBridge.getSettings(), "application/json")
                }
                "/api/send" -> send(session)
                "/api/stream" -> stream(session)
                "/api/poll" -> poll(session)
                "/api/upload" -> upload(session)
                "/api/compress" -> compress()
                "/api/image" -> image(session)
                "/api/file" -> file(session)
                "/api/stop" -> {
                    WebBridge.stop()
                    fixed("""{"ok":true}""", "application/json")
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"${(e.message ?: "error").replace("\"", "'")}"}"""
            )
        }
    }

    private fun page(): Response {
        val html = runCatching {
            assets.open("web/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { "<h1>MiniDS</h1><p>页面资源缺失</p>" }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun fixed(body: String, mime: String): Response =
        newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", body)

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    /** 直接按字节读 body，避免 NanoHTTPD 猜错编码导致中文乱码。 */
    private fun readBody(session: IHTTPSession): ByteArray {
        val length = session.headers["content-length"]?.toIntOrNull() ?: -1
        if (length > 0) {
            val buffer = ByteArray(length)
            var offset = 0
            val input = session.inputStream
            while (offset < length) {
                val n = input.read(buffer, offset, length - offset)
                if (n <= 0) break
                offset += n
            }
            return if (offset == length) buffer else buffer.copyOf(offset)
        }
        val params = HashMap<String, String>()
        session.parseBody(params)
        return params["postData"].orEmpty().toByteArray(Charsets.UTF_8)
    }

    private fun send(session: IHTTPSession): Response {
        val raw = String(readBody(session), Charsets.UTF_8)
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val text = obj?.get("text")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val thinking = obj?.get("thinking")?.jsonPrimitive?.booleanOrNull ?: false
        val search = obj?.get("search")?.jsonPrimitive?.booleanOrNull ?: false
        val editId = obj?.get("editId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val atts = runCatching {
            obj?.get("attachments")?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["fileId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                WebAtt(
                    fileId = id,
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: "文件",
                    isImage = o["isImage"]?.jsonPrimitive?.booleanOrNull ?: false,
                    localPath = o["localPath"]?.jsonPrimitive?.contentOrNull
                )
            }.orEmpty()
        }.getOrDefault(emptyList())

        if (text.isEmpty() && atts.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"empty"}"""
            )
        }
        if (WebBridge.streaming()) {
            return newFixedLengthResponse(
                Response.Status.CONFLICT, "application/json", """{"error":"busy"}"""
            )
        }

        WebStream.reset()
        val sink = object : WebSink {
            override fun think(text: String) = WebStream.add("think", text)
            override fun text(text: String) = WebStream.add("text", text)
            override fun status(text: String) = WebStream.add("status", text)
            override fun sources(json: String) {
                WebStream.add("sources", json)
            }
            override fun usage(text: String) = WebStream.add("usage", text)
            override fun finish(error: String?) = WebStream.finish(error)
        }

        val handler = WebBridge.send
        if (handler == null) {
            sink.finish("服务未就绪")
        } else {
            try {
                handler.invoke(text, thinking, search, atts, editId, sink)
            } catch (e: Exception) {
                sink.finish("服务异常：${e.message ?: "未知错误"}")
            }
        }
        return fixed("""{"ok":true}""", "application/json")
    }

    private fun setSettings(session: IHTTPSession): Response {
        val raw = String(readBody(session), Charsets.UTF_8)
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val thinking = obj?.get("thinking")?.jsonPrimitive?.booleanOrNull
        val search = obj?.get("search")?.jsonPrimitive?.booleanOrNull
        WebBridge.setSettings?.invoke(thinking, search)
        return fixed("""{"ok":true}""", "application/json")
    }

    private fun poll(session: IHTTPSession): Response {
        val from = session.parameters["from"]?.firstOrNull()?.toIntOrNull() ?: 0
        val (list, done, total) = WebStream.await(from, 20_000)
        return eventsJson(list, done, total)
    }

    private fun eventsJson(list: List<String>, done: Boolean, total: Int): Response {
        val body = buildJsonObject {
            put("events", buildJsonArray {
                list.forEach { add(json.parseToJsonElement(it)) }
            })
            put("done", done)
            put("next", total)
        }
        return fixed(body.toString(), "application/json")
    }

    private fun stream(session: IHTTPSession): Response {
        val resume = session.headers["last-event-id"]?.toIntOrNull()
            ?: session.parameters["from"]?.firstOrNull()?.toIntOrNull()
            ?: 0
        return StreamResponse(resume.coerceAtLeast(0))
    }

    /**
     * 真正的 SSE 推流：直接写 socket，手写 chunked 帧，绕开 NanoHTTPD 的固定长度响应。
     * 每个事件立刻 flush 出去，不再攒批。
     */
    private inner class StreamResponse(private val start: Int) : Response(
        Response.Status.OK,
        "text/event-stream; charset=utf-8",
        null,
        0L
    ) {
        override fun send(outputStream: java.io.OutputStream) {
            val out = outputStream
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: keep-alive\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n"
            var index = start
            try {
                out.write(head.toByteArray(Charsets.UTF_8))
                out.flush()
                while (true) {
                    val (events, done, total) = WebStream.await(index, 25_000, 0)
                    if (events.isNotEmpty()) {
                        val sb = StringBuilder()
                        events.forEachIndexed { i, e ->
                            sb.append("id: ").append(index + i).append('\n')
                            sb.append("data: ").append(e).append("\n\n")
                        }
                        index += events.size
                        chunk(out, sb.toString())
                        out.flush()
                    }
                    if (done && index >= total) break
                    if (events.isEmpty()) {
                        // 保活，顺便探测连接是否还在
                        chunk(out, ": ping\n\n")
                        out.flush()
                    }
                }
                out.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: java.io.IOException) {
                // 网页关了或断网，结束即可
            }
        }

        private fun chunk(out: java.io.OutputStream, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write((Integer.toHexString(bytes.size) + "\r\n").toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun upload(session: IHTTPSession): Response {
        val bytes = readBody(session)
        if (bytes.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"empty file"}"""
            )
        }
        val rawName = session.headers["x-filename"] ?: "file"
        val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
        val mime = session.headers["content-type"] ?: "application/octet-stream"
        val localOnly = session.parameters["mode"]?.firstOrNull() == "local"
        val result = if (localOnly) {
            WebBridge.saveLocal?.invoke(name, mime, bytes) ?: """{"error":"服务未就绪"}"""
        } else {
            WebBridge.upload?.invoke(name, mime, bytes) ?: """{"error":"服务未就绪"}"""
        }
        return fixed(result, "application/json")
    }

    private fun compress(): Response {
        val handler = WebBridge.compress ?: return fixed("""{"ok":false}""", "application/json")
        val ok = handler.invoke()
        return fixed("""{"ok":$ok}""", "application/json")
    }

    private fun image(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull() ?: return notFound()
        val path = WebFiles.get(id) ?: WebBridge.imagePath(id) ?: return notFound()
        val file = File(path)
        if (!file.exists() || !file.isFile) return notFound()
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return newChunkedResponse(Response.Status.OK, mime, FileInputStream(file)).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    /** 原样返回上传的文件（文本用于网页端再次编辑）；文本统一转成 UTF-8。 */
    private fun file(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull() ?: return notFound()
        val path = WebFiles.get(id) ?: WebBridge.imagePath(id) ?: return notFound()
        val f = File(path)
        if (!f.exists() || !f.isFile) return notFound()
        val mime = when (f.extension.lowercase()) {
            "txt", "md", "csv", "json", "log" -> "text/plain; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
        val isText = mime.startsWith("text/")
        val body = if (isText) {
            val utf8 = TextCodec.utf8(TextCodec.decode(f.readBytes()))
            newFixedLengthResponse(
                Response.Status.OK,
                mime,
                java.io.ByteArrayInputStream(utf8),
                utf8.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
        }
        return body.apply { addHeader("Cache-Control", "no-store") }
    }
}
