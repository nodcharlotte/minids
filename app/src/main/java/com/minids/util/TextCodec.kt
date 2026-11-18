package com.minids.util

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本文件编码识别。
 * 手机上拿到的 txt 常常来自 Windows 记事本（GBK/ANSI），
 * 直接按 UTF-8 读会变成乱码，所以这里先认 BOM，再试严格 UTF-8，最后按 GB18030 解。
 * 写回一律用 UTF-8。
 */
object TextCodec {

    private val gb: Charset = Charset.forName("GB18030")

    private val textExt = listOf(
        ".txt", ".md", ".csv", ".json", ".log", ".xml",
        ".yaml", ".yml", ".ini", ".html", ".htm", ".srt"
    )

    fun isTextName(name: String): Boolean {
        val n = name.lowercase()
        return textExt.any { n.endsWith(it) }
    }

    fun isText(mime: String, name: String): Boolean =
        mime.startsWith("text/") || mime.contains("json", true) || isTextName(name)

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return runCatching { strictUtf8(bytes) }.getOrElse { String(bytes, gb) }
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
}
