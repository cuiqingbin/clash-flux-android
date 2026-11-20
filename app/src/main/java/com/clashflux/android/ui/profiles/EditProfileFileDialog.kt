package com.clashflux.android.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clashflux.android.core.ClashConfigEditor
import com.clashflux.android.theme.VergePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LARGE_FILE_PREVIEW_THRESHOLD_CHARS = 250_000
private const val PREVIEW_MAX_LINES = 2_000

@Composable
fun EditProfileFileDialog(
    profileName: String,
    initialContent: String,
    isSaving: Boolean = false,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    var validationError by remember(initialContent) { mutableStateOf<String?>(null) }
    val isLargeInitialContent = remember(initialContent) {
        initialContent.length > LARGE_FILE_PREVIEW_THRESHOLD_CHARS
    }
    var forceEditLargeFile by remember(initialContent) { mutableStateOf(false) }
    val inReadOnlyPreview = isLargeInitialContent && !forceEditLargeFile
    val lineCount by remember(content) {
        derivedStateOf {
            if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        }
    }
    val previewLines by produceState<List<String>>(
        initialValue = emptyList(),
        key1 = initialContent,
        key2 = inReadOnlyPreview
    ) {
        value = if (!inReadOnlyPreview) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val lines = initialContent.lineSequence().take(PREVIEW_MAX_LINES + 1).toList()
                if (lines.size > PREVIEW_MAX_LINES) {
                    lines.take(PREVIEW_MAX_LINES) + "...（文件过大，预览已截断至前 $PREVIEW_MAX_LINES 行）"
                } else {
                    lines
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = !isSaving
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (inReadOnlyPreview) "查看文件（只读预览）" else "编辑文件",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$profileName ($lineCount 行 · ${content.length / 1024} KB)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (inReadOnlyPreview) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF59E0B).copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "配置超过 250KB，直接全文编辑容易卡顿，建议优先使用「编辑节点 / 编辑规则」。",
                            fontSize = 11.sp,
                            color = Color(0xFFB45309),
                            lineHeight = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = { forceEditLargeFile = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("仍要编辑", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VergePrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                    ) {
                        itemsIndexed(previewLines) { idx, line ->
                            Text(
                                text = "${idx + 1} | $line",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                } else {
                    // Text Editor
                    OutlinedTextField(
                        value = content,
                        onValueChange = {
                            content = it
                            validationError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        isError = validationError != null,
                        placeholder = { Text("YAML 配置文件内容...") }
                    )
                }

                val displayError = validationError ?: saveError
                if (!displayError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = displayError,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Footer Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = if (inReadOnlyPreview) 0.dp else 12.dp)
                    ) {
                        Text(if (inReadOnlyPreview) "关闭" else "取消")
                    }

                    if (!inReadOnlyPreview) {
                        Button(
                            onClick = {
                                val err = ClashConfigEditor.validateYamlSyntax(content)
                                if (err != null) {
                                    validationError = err
                                } else {
                                    validationError = null
                                    onSave(ClashConfigEditor.sanitizeYaml(content))
                                }
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = VergePrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("保存中...", color = Color.White)
                            } else {
                                Text("保存", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
