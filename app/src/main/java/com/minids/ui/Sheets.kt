package com.minids.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minids.UiState
import kotlinx.coroutines.delay
import kotlin.math.log2

private const val MIN_EXP = 11f
private const val MAX_EXP = 20f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onApiKey: (String) -> Unit,
    onContext: (Int) -> Unit,
    onThinking: (Boolean) -> Unit,
    onSearch: (Boolean) -> Unit,
    onDark: (Boolean) -> Unit,
    onLoadBalance: () -> Unit,
    onModelRefresh: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var exp by remember { mutableStateOf(log2(state.settings.contextTokens.toFloat())) }
    var changing by remember { mutableStateOf(false) }
    var replaceAttempted by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    val hasKey = state.settings.apiKey.isNotBlank()

    LaunchedEffect(Unit) { onLoadBalance() }

    // 关闭时：有草稿就保存（保存会刷新名称）；本次点过「更换」但最终没保存也刷新一次
    val closeAndSave: () -> Unit = {
        val draft = keyDraft.trim()
        if (draft.isNotBlank()) {
            onApiKey(draft)
        } else if (replaceAttempted) {
            onModelRefresh()
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = closeAndSave,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 30.dp)
        ) {
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))

            Text("API Key", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (hasKey && !changing) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已保存", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "更换",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                changing = true
                                replaceAttempted = true
                                keyDraft = ""
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            } else {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("粘贴 sk- 开头的 Key", fontSize = 14.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                )
                if (hasKey) {
                    Text(
                        "取消更换",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                changing = false
                                keyDraft = ""
                            }
                            .padding(4.dp)
                    )
                }
            }
            Text(
                "只保存在本机，设置关闭后不再回显",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp)
            )

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("上下文长度", fontSize = 14.sp)
                Text(
                    ctxLabel(1 shl exp.toInt()),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = exp,
                onValueChange = {
                    exp = it
                    onContext(1 shl it.toInt())
                },
                valueRange = MIN_EXP..MAX_EXP,
                steps = 8
            )
            Text(
                "2K – 1M tokens。超出预算时从最早的消息开始丢弃，摘要始终保留。",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp)
            )

            Spacer(Modifier.height(22.dp))
            ToggleRow(
                title = "深度思考",
                subtitle = "开启后用低强度思考，回答更稳，首字更慢。",
                checked = state.settings.thinking,
                onChange = onThinking
            )

            Spacer(Modifier.height(16.dp))
            ToggleRow(
                title = "联网搜索",
                subtitle = "回答前先上网查，并附参考来源。按次计费。",
                checked = state.settings.search,
                onChange = onSearch
            )

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("外观", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("浅色", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                ToggleSlider(checked = state.settings.dark, onChange = onDark)
                Spacer(Modifier.width(8.dp))
                Text("深色", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "模型固定 deepseek-flash（唯一支持图片的型号）",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("账户余额", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                when {
                    state.balanceLoading -> Text(
                        "查询中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    state.balanceError != null -> Text(
                        state.balanceError,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )

                    state.balance != null -> Text(
                        state.balance,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )

                    else -> Text(
                        "—",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (checked) "开" else "关",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            ToggleSlider(checked = checked, onChange = onChange)
        }
        Text(
            subtitle,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * 自绘开关：关的时候圆点始终停在左侧，开的时候停在右侧，两态颜色区分明显。
 */
@Composable
fun ToggleSlider(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val offsetX by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        label = "toggleThumb"
    )
    val track = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant
    val thumb = if (checked) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        Modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(enabled = enabled) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .offset(x = offsetX)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(
    onDismiss: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
    onCompress: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionCard(
                    title = "图片",
                    sub = "JPEG / PNG / GIF / WebP",
                    modifier = Modifier.weight(1f),
                    onClick = onImage
                )
                ActionCard(
                    title = "文件",
                    sub = "PDF / Word / Excel / PPT",
                    modifier = Modifier.weight(1f),
                    onClick = onFile
                )
            }
            Spacer(Modifier.height(10.dp))
            ActionCard(
                title = "压缩会话",
                sub = "保留最近 10 轮，更早的整理成摘要",
                modifier = Modifier.fillMaxWidth(),
                onClick = onCompress
            )
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    sub: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("›", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            sub,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

private fun ctxLabel(v: Int): String = when {
    v >= 1 shl 20 -> "1M"
    v >= 1024 -> "${v / 1024}K"
    else -> "$v"
}
