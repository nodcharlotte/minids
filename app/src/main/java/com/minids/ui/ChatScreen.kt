package com.minids.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minids.ChatViewModel
import com.minids.R
import com.minids.UiState
import com.minids.data.Attachment
import com.minids.data.Msg
import com.minids.data.Source
import com.minids.util.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class Sheet { None, Add, Settings }

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var sheet by remember { mutableStateOf(Sheet.None) }

    var pickingForEdit by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            if (pickingForEdit) vm.attachToEdit(uri) else vm.attach(uri, true)
        }
        pickingForEdit = false
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attach(uri, false)
    }

    LaunchedEffect(s.notice) {
        val n = s.notice
        if (n != null) {
            Toast.makeText(ctx, n, Toast.LENGTH_SHORT).show()
            vm.consumeNotice()
        }
    }

    val msgs = s.chat.messages
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text?.length, msgs.lastOrNull()?.reasoning?.length) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
        ) {
            TopBar(onSettings = { sheet = Sheet.Settings }, onNew = vm::newChat)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(msgs, key = { it.id }) { m ->
                        if (m.id == s.editingId) {
                            EditBox(
                                state = s,
                                onText = vm::setEditText,
                                onAddImage = {
                                    pickingForEdit = true
                                    pickImage.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onRemove = vm::removeEditAttachment,
                                onSend = vm::submitEdit,
                                onCancel = vm::cancelEdit,
                                onEditFile = { att, text, done -> vm.replaceEditAttachment(att.fileId, text, done) }
                            )
                        } else {
                            MessageRow(
                                m = m,
                                streaming = s.streaming && m.id == msgs.lastOrNull()?.id,
                                thinkingOn = s.settings.thinking,
                                onEdit = vm::startEdit
                            )
                        }
                    }
                }
            }
        }

        Composer(
            state = s,
            onDraft = vm::setDraft,
            onPlus = { sheet = Sheet.Add },
            onSend = { if (s.streaming) vm.stop() else vm.send() },
            onRemove = vm::removePending
        )
        }
    }

    when (sheet) {
        Sheet.Add -> AddSheet(
            onDismiss = { sheet = Sheet.None },
            onImage = {
                sheet = Sheet.None
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFile = {
                sheet = Sheet.None
                pickFile.launch(arrayOf("*/*"))
            },
            onCompress = {
                sheet = Sheet.None
                vm.compress()
            }
        )

        Sheet.Settings -> SettingsSheet(
            state = s,
            onDismiss = { sheet = Sheet.None },
            onApiKey = vm::saveApiKey,
            onContext = vm::saveContextTokens,
            onThinking = vm::saveThinking,
            onSearch = vm::saveSearch,
            onDark = vm::saveDark,
            onLoadBalance = vm::loadBalance,
            onModelRefresh = vm::refreshModelName,
            onWeb = vm::setWeb
        )

        Sheet.None -> Unit
    }
}

/* ---------------- 顶栏 ---------------- */

@Composable
private fun TopBar(onSettings: () -> Unit, onNew: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "＋",
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onNew)
                .padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }
}

/* ---------------- 空状态 ---------------- */

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MiniDS", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "只有聊天。图片、文件、联网、压缩。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ---------------- 消息 ---------------- */

@Composable
private fun MessageRow(
    m: Msg,
    streaming: Boolean,
    thinkingOn: Boolean,
    onEdit: (String) -> Unit
) {
    when (m.role) {
        "user" -> UserMessage(m) { onEdit(m.id) }
        "system" -> SummaryRow(m)
        else -> AssistantMessage(m, streaming, thinkingOn)
    }
}

@Composable
private fun UserMessage(m: Msg, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.attachments.isNotEmpty()) {
            Row(
                Modifier
                    .padding(bottom = 6.dp)
                    .clickable(onClick = onEdit),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                m.attachments.forEach { a ->
                    if (a.isImage) Thumb(a.localPath, 108.dp) else FileChip(a.name, 108.dp)
                }
            }
        }
        if (m.text.isNotBlank()) {
            Box(
                Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                SelectionContainer {
                    Text(m.text, fontSize = 16.sp, lineHeight = 23.sp)
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(m: Msg, streaming: Boolean, thinkingOn: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (m.searchStatus != null) {
            Row(
                Modifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dot(true)
                Spacer(Modifier.width(8.dp))
                Text(
                    m.searchStatus,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (m.reasoning.isNotBlank() || (thinkingOn && streaming && m.text.isEmpty())) {
            ThinkingPanel(m, streaming)
        }

        if (m.text.isNotBlank()) {
            SelectionContainer {
                Markdown(m.text)
            }
        } else if (streaming && m.reasoning.isEmpty() && m.searchStatus == null && !thinkingOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(true)
                Spacer(Modifier.width(8.dp))
                Text("正在生成", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (m.error != null) {
            Text(
                m.error,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (m.sources.isNotEmpty()) {
            SourcesBlock(m.sources)
        }
    }
}

@Composable
private fun SummaryRow(m: Msg) {
    var open by rememberSaveable(m.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (open) "收起摘要" else "已压缩的早前对话",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
        if (open) {
            Text(
                m.text,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
            )
        }
    }
}

@Composable
private fun ThinkingPanel(m: Msg, streaming: Boolean) {
    var open by rememberSaveable(m.id) { mutableStateOf(false) }
    val active = streaming && m.text.isEmpty()
    val title = when {
        active -> "思考中"
        m.thinkingMs > 0 -> "已深度思考 ${(m.thinkingMs / 1000).coerceAtLeast(1)}s"
        else -> "已深度思考"
    }

    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = m.reasoning.isNotBlank()) { open = !open }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(active)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.reasoning.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    if (open) "▾" else "▸",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (open) {
            Text(
                m.reasoning.ifBlank { "…" },
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp)
            )
        }
    }
}

@Composable
private fun SourcesBlock(sources: List<Source>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "参考来源 ${sources.size}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (open) "  ▾" else "  ▸",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (open) {
            sources.forEach { src ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(ctx, src.url) }
                        .padding(vertical = 7.dp)
                ) {
                    Text(
                        src.title,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        src.site ?: hostOf(src.url),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)

/* ---------------- 输入区 ---------------- */

@Composable
private fun Composer(
    state: UiState,
    onDraft: (String) -> Unit,
    onPlus: () -> Unit,
    onSend: () -> Unit,
    onRemove: (String) -> Unit
) {
    val canSend = state.draft.isNotBlank() || state.pending.isNotEmpty() || state.streaming

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
    ) {
        if (state.pending.isNotEmpty()) {
            Row(
                Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.pending.forEach { a ->
                    Box {
                        if (a.isImage) Thumb(a.localPath, 56.dp) else FileChip(a.name, 56.dp)
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC222222))
                                .clickable { onRemove(a.fileId) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("×", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (state.uploading) {
            Text(
                "正在上传…",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
            )
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                Modifier
                    .heightIn(min = 50.dp)
                    .padding(start = 3.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPlus),
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋", fontSize = 22.sp)
                }

                Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    val fieldStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None
                        )
                    )
                    BasicTextField(
                        value = state.draft,
                        onValueChange = onDraft,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = fieldStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        maxLines = 6,
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (state.draft.isEmpty()) {
                                    Text(
                                        text = "发消息…",
                                        style = fieldStyle.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.55f)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                Spacer(Modifier.width(4.dp))

                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.streaming) "■" else "↑",
                        fontSize = 14.sp,
                        color = if (canSend) MaterialTheme.colorScheme.background
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                state.usage,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                state.settings.modelName ?: "deepseek-flash",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/* ---------------- 编辑重发 ---------------- */

@Composable
private fun EditBox(
    state: UiState,
    onText: (String) -> Unit,
    onAddImage: () -> Unit,
    onRemove: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onEditFile: (Attachment, String, (Boolean) -> Unit) -> Unit
) {
    var editingFile by remember { mutableStateOf<Attachment?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (state.editingAttachments.isNotEmpty()) {
            Row(
                Modifier.padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.editingAttachments.forEach { a ->
                    Box {
                        if (a.isImage) {
                            Thumb(a.localPath, 72.dp)
                        } else {
                            Box(Modifier.clickable { editingFile = a }) {
                                FileChip(a.name, 72.dp)
                            }
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC222222))
                                .clickable { onRemove(a.fileId) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("×", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = state.editingText,
                    onValueChange = onText,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    maxLines = 6
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "取消",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.uploading) {
                        Text(
                            "上传中…",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onAddImage),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("＋", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface)
                            .clickable(onClick = onSend),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("↑", fontSize = 14.sp, color = MaterialTheme.colorScheme.background)
                    }
                }
            }
        }
    }

    editingFile?.let { att ->
        TextFileDialog(
            att = att,
            onDismiss = { editingFile = null },
            onSave = { text, done -> onEditFile(att, text, done) }
        )
    }
}

@Composable
private fun TextFileDialog(
    att: Attachment,
    onDismiss: () -> Unit,
    onSave: (String, (Boolean) -> Unit) -> Unit
) {
    var text by remember(att.fileId) { mutableStateOf("") }
    var loaded by remember(att.fileId) { mutableStateOf(false) }
    var saving by remember(att.fileId) { mutableStateOf(false) }
    LaunchedEffect(att.fileId) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(att.localPath.orEmpty()).readBytes()
                TextCodec.decode(bytes)
            }.getOrDefault("")
        }
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(att.name, fontSize = 15.sp, maxLines = 1) },
        text = {
            if (!loaded) {
                Text("读取中…", fontSize = 13.sp)
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface)
                )
            }
        },
        confirmButton = {
            val enabled = loaded && !saving
            Text(
                if (saving) "保存中…" else "保存",
                fontSize = 14.sp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) {
                        saving = true
                        onSave(text) { ok ->
                            saving = false
                            if (ok) onDismiss()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        },
        dismissButton = {
            Text(
                "取消",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    )
}

/* ---------------- 小组件 ---------------- */

@Composable
private fun Dot(active: Boolean) {
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "dot")
        val a by transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "alpha"
        )
        a
    } else {
        0.45f
    }
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

@Composable
private fun Thumb(path: String?, size: Dp) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            path?.let { p ->
                runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("图片", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileChip(name: String, size: Dp) {
    val ext = name.substringAfterLast('.', "").uppercase().take(4).ifBlank { "FILE" }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ext, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                name,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}
