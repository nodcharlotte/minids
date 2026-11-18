package com.minids.data

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val fileId: String,
    val name: String,
    val isImage: Boolean,
    val localPath: String? = null
)

@Serializable
data class Source(
    val title: String,
    val url: String,
    val site: String? = null
)

@Serializable
data class Msg(
    val id: String,
    val role: String,
    val text: String = "",
    val reasoning: String = "",
    val attachments: List<Attachment> = emptyList(),
    val sources: List<Source> = emptyList(),
    val toolCalls: String? = null,
    val searchStatus: String? = null,
    val error: String? = null,
    val thinkingMs: Long = 0L,
    val ts: Long = 0L
)

@Serializable
data class Chat(
    val messages: List<Msg> = emptyList(),
    val updatedAt: Long = 0L
)
