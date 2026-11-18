package com.minids.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Code(val lang: String, val body: String) : Block
    data class Head(val level: Int, val body: String) : Block
    data class Par(val body: String) : Block
    data class Bullet(val body: String) : Block
    data class Quote(val body: String) : Block
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parse(text) }
    val line = MaterialTheme.colorScheme.outline
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(7.dp))
            when (block) {
                is Block.Code -> CodeBlock(block.body)
                is Block.Head -> Text(
                    text = inlineText(block.body),
                    fontSize = (19 - block.level).coerceAtLeast(15).sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )

                is Block.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text("·", fontSize = 16.sp, modifier = Modifier.width(14.dp))
                    Text(inlineText(block.body), fontSize = 16.sp, lineHeight = 25.sp)
                }

                is Block.Quote -> Text(
                    inlineText(block.body),
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .drawBehind {
                            drawLine(
                                color = line,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 3f
                            )
                        }
                        .padding(start = 12.dp)
                )

                is Block.Par -> Text(inlineText(block.body), fontSize = 16.sp, lineHeight = 25.sp)
            }
        }
    }
}

@Composable
private fun CodeBlock(body: String) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .horizontalScroll(scroll)
    ) {
        Text(
            text = body,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
    }
}

private fun parse(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = src.replace("\r\n", "\n").split("\n")
    val buffer = StringBuilder()

    fun flush() {
        val t = buffer.toString().trim()
        if (t.isNotEmpty()) out += Block.Par(t)
        buffer.clear()
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()
        when {
            line.startsWith("```") -> {
                flush()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.append(lines[i]).append("\n")
                    i++
                }
                out += Block.Code("", body.toString().trimEnd())
            }

            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                out += Block.Head(level, line.substring(level).trim())
            }

            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                flush()
                out += Block.Bullet(line.drop(2).trim())
            }

            line.startsWith("> ") -> {
                flush()
                out += Block.Quote(line.drop(2).trim())
            }

            line.isEmpty() -> flush()

            else -> {
                if (buffer.isNotEmpty()) buffer.append("\n")
                buffer.append(raw)
            }
        }
        i++
    }
    flush()
    return if (out.isEmpty()) listOf(Block.Par(src)) else out
}

private fun inlineText(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i])
                    i++
                }
            }

            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(s[i])
                    i++
                }
            }

            s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' -> {
                val end = s.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(s[i])
                    i++
                }
            }

            else -> {
                append(s[i])
                i++
            }
        }
    }
}
