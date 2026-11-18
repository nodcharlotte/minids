package com.minids.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

class ChatRepo(ctx: Context) {

    private val file = File(ctx.filesDir, "chat.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Chat = runCatching {
        if (file.exists()) json.decodeFromString<Chat>(file.readText()) else Chat()
    }.getOrDefault(Chat())

    fun save(chat: Chat) {
        runCatching { file.writeText(json.encodeToString(Chat.serializer(), chat)) }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
