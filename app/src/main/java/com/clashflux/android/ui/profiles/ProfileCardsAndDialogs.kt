package com.clashflux.android.ui.profiles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.core.ClashConfigEditor
import com.clashflux.android.data.ProfileRepository
import com.clashflux.android.model.Profile
import com.clashflux.android.model.TrafficStats
import com.clashflux.android.theme.StatusConnected
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.ui.components.VergeCard
import com.clashflux.android.ui.components.readClipboardText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileCardItem(
    profile: Profile,
    isUpdating: Boolean = false,
    onSelect: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onShowMenu: () -> Unit
) {
    val borderColor = if (profile.isSelected) VergePrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    VergeCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onShowMenu
            ),
        borderColor = borderColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (profile.isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Active",
                        tint = StatusConnected,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column {
                    Text(
                        text = profile.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(profile.updatedAt))
                    val expireText = if (profile.expireTimestamp > 0L) {
                        val expStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(profile.expireTimestamp))
                        "  ·  到期: $expStr"
                    } else {
                        ""
                    }
                    Text(
                        text = "更新时间: $dateStr$expireText",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUpdate, enabled = !isUpdating) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = VergePrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Update",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(onClick = onShowMenu) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onDelete, enabled = !isUpdating) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (profile.totalBytes > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已用: ${TrafficStats.formatBytes(profile.usedBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "总量: ${TrafficStats.formatBytes(profile.totalBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { profile.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (profile.progress > 0.85f) Color(0xFFEF4444) else VergePrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!profile.isSelected) {
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "使用此配置",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun AddProfileDialog(
    isAdding: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: suspend (name: String, url: String) -> Result<Unit>,
    onImportLocalFile: suspend (name: String, yamlText: String) -> Result<Unit>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            dialogError = null
            readPickedConfigFile(context, uri).onSuccess { (fileName, yamlText) ->
                val resolvedName = name.filter { it >= ' ' }.trim().ifBlank { fileName }
                val res = onImportLocalFile(resolvedName, yamlText)
                if (res.isFailure) {
                    dialogError = res.exceptionOrNull()?.message ?: "本地配置导入失败"
                }
            }.onFailure { err ->
                dialogError = err.message ?: "读取本地配置文件失败"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "添加订阅配置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        dialogError = null
                    },
                    enabled = !isAdding,
                    label = { Text("配置名称 (可选)") },
                    placeholder = { Text("留空将自动根据链接或文件名命名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        dialogError = null
                    },
                    enabled = !isAdding,
                    label = { Text("订阅 URL / 节点链接 / Base64") },
                    placeholder = { Text("支持 HTTP(S)、vless://、vmess:// 等") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (url.isNotEmpty() && !isAdding) {
                            IconButton(onClick = {
                                url = ""
                                dialogError = null
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                )

                if (url.trim().startsWith("http://", ignoreCase = true)) {
                    Text(
                        text = "提示：当前使用明文 HTTP 订阅，建议优先使用 HTTPS 以保障配置传输安全",
                        fontSize = 11.sp,
                        color = Color(0xFFD97706),
                        lineHeight = 15.sp
                    )
                }

                if (dialogError != null) {
                    Text(
                        text = dialogError.orEmpty(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 16.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val cleanClip = ClashConfigEditor.sanitizeYaml(readClipboardText(context)).trim()
                            if (cleanClip.isNotEmpty()) {
                                url = cleanClip
                                dialogError = null
                                if (name.isBlank()) {
                                    name = ProfileRepository.deriveNameFromInput(cleanClip)
                                }
                            }
                        },
                        enabled = !isAdding,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("剪贴板", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !isAdding,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "Import Local File",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("本地 YAML", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanUrl = ClashConfigEditor.sanitizeYaml(url).trim()
                    if (cleanUrl.isNotBlank()) {
                        val finalName = name.filter { it >= ' ' }.trim()
                            .ifBlank { ProfileRepository.deriveNameFromInput(cleanUrl) }
                        dialogError = null
                        scope.launch {
                            val res = onConfirm(finalName, cleanUrl)
                            if (res.isFailure) {
                                dialogError = res.exceptionOrNull()?.message ?: "导入失败，请检查链接或网络"
                            }
                        }
                    }
                },
                enabled = url.isNotBlank() && !isAdding
            ) {
                if (isAdding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导入中...")
                } else {
                    Text("添加并导入")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isAdding) {
                Text("取消")
            }
        }
    )
}

internal suspend fun writeExportedYaml(context: Context, uri: Uri, content: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入目标文件")
        }
    }

private suspend fun readPickedConfigFile(context: Context, uri: Uri): Result<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        runCatching {
            var displayName = "本地导入配置"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { raw ->
                            displayName = raw.substringBeforeLast('.').ifBlank { raw }
                        }
                    }
                }
            }
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                ProfileRepository.readBoundedUtf8Stream(
                    stream = stream,
                    maxBytes = ProfileRepository.MAX_SUBSCRIPTION_BYTES,
                    errorLabel = "配置文件"
                )
            } ?: throw IllegalStateException("无法打开所选文件")
            displayName to text
        }
    }

@Composable
internal fun <T, R> ProfileAsyncEditorHost(
    loadKey: Any,
    loadData: suspend () -> T,
    onDismiss: () -> Unit,
    onSave: suspend (R) -> Boolean,
    successMessage: String,
    failureMessage: String,
    loadingMessage: String = "正在加载配置...",
    content: @Composable (
        initialData: T,
        isSaving: Boolean,
        saveError: String?,
        onDismiss: () -> Unit,
        onSave: (R) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadedData by remember(loadKey) { mutableStateOf<T?>(null) }
    var loadError by remember(loadKey) { mutableStateOf<String?>(null) }
    var isSaving by remember(loadKey) { mutableStateOf(false) }
    var saveError by remember(loadKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(loadKey) {
        loadedData = null
        loadError = null
        runCatching { loadData() }
            .onSuccess { loadedData = it }
            .onFailure { loadError = it.message ?: "配置加载失败" }
    }

    val currentData = loadedData
    if (currentData == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (loadError != null) "加载失败" else "请稍候") },
            text = {
                if (loadError != null) {
                    Text(
                        text = loadError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(loadingMessage, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(if (loadError != null) "关闭" else "取消")
                }
            }
        )
        return
    }

    content(
        currentData,
        isSaving,
        saveError,
        { if (!isSaving) onDismiss() }
    ) { updatedData ->
        if (isSaving) return@content
        isSaving = true
        saveError = null
        scope.launch {
            val ok = runCatching { onSave(updatedData) }.getOrDefault(false)
            isSaving = false
            Toast.makeText(
                context,
                if (ok) successMessage else failureMessage,
                Toast.LENGTH_SHORT
            ).show()
            if (ok) {
                onDismiss()
            } else {
                saveError = "$failureMessage，请检查配置内容或重试（当前修改已保留）"
            }
        }
    }
}
