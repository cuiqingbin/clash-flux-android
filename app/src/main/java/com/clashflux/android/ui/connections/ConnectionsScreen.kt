package com.clashflux.android.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.model.ConnectionItem
import com.clashflux.android.model.LogEntry
import com.clashflux.android.model.TrafficStats
import com.clashflux.android.theme.TrafficDownload
import com.clashflux.android.theme.TrafficUpload
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.ui.components.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_UID_LABEL_CACHE = 256

@Composable
fun ConnectionsScreen(
    connections: List<ConnectionItem>,
    onCloseAll: () -> Unit,
    onCloseConnection: (String) -> Unit,
    modifier: Modifier = Modifier,
    logs: List<LogEntry> = emptyList(),
    currentLogLevel: String = "info",
    onLogLevelChange: (String) -> Unit = {},
    onClearLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    val uidLabelCache = remember { mutableStateMapOf<Int, String>() }
    val uidLabelOrder = remember { ArrayDeque<Int>() }
    LaunchedEffect(connections) {
        val missingUids = connections
            .asSequence()
            .map { it.metadata.uid }
            .filter { it > 0 && it !in uidLabelCache }
            .distinct()
            .toList()
        if (missingUids.isEmpty()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            missingUids.associateWith { uid -> lookupAppLabelForUid(pm, uid) }
        }
        resolved.forEach { (uid, label) ->
            uidLabelCache[uid] = label
            uidLabelOrder.remove(uid)
            uidLabelOrder.addLast(uid)
        }
        while (uidLabelOrder.size > MAX_UID_LABEL_CACHE) {
            val oldestUid = uidLabelOrder.removeFirst()
            uidLabelCache.remove(oldestUid)
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = 连接记录, 1 = 实时日志
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedLogLevel by rememberSaveable { mutableStateOf("ALL") }
    var isLogPaused by remember { mutableStateOf(false) }
    var pausedLogsSnapshot by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    val displayedLogs = if (isLogPaused) pausedLogsSnapshot else logs

    val uidLabelsVersion = uidLabelCache.size
    val filteredConnections = remember(connections, searchQuery, uidLabelsVersion) {
        val query = searchQuery.trim()
        val mapped = connections.map { item ->
            item to formatProcessLabel(item.metadata, uidLabelCache[item.metadata.uid].orEmpty())
        }
        if (query.isEmpty()) {
            mapped
        } else {
            mapped.filter { (item, procLabel) ->
                val host = item.metadata.host.ifEmpty { item.metadata.destinationIP }
                val chain = item.chains.joinToString(" ")
                host.contains(query, ignoreCase = true) ||
                    item.rule.contains(query, ignoreCase = true) ||
                    chain.contains(query, ignoreCase = true) ||
                    item.metadata.network.contains(query, ignoreCase = true) ||
                    procLabel.contains(query, ignoreCase = true)
            }
        }
    }

    val filteredLogs = remember(displayedLogs, selectedLogLevel, searchQuery) {
        val query = searchQuery.trim()
        displayedLogs.filter { log ->
            val levelMatch = selectedLogLevel == "ALL" || log.type.equals(selectedLogLevel, ignoreCase = true)
            val queryMatch = query.isEmpty() ||
                log.payload.contains(query, ignoreCase = true) ||
                log.type.contains(query, ignoreCase = true)
            levelMatch && queryMatch
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "连接详情与日志",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "活动连接: ${connections.size} 个 · 内核日志: ${logs.size} 条",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedTab == 1) {
                    IconButton(
                        onClick = {
                            if (isLogPaused) {
                                isLogPaused = false
                            } else {
                                pausedLogsSnapshot = logs
                                isLogPaused = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isLogPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isLogPaused) "恢复滚动" else "暂停日志",
                            tint = if (isLogPaused) Color(0xFFF59E0B) else VergePrimary
                        )
                    }
                }
                IconButton(
                    onClick = {
                        if (selectedTab == 0) {
                            onCloseAll()
                        } else {
                            pausedLogsSnapshot = emptyList()
                            onClearLogs()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear List",
                        tint = Color(0xFFEF4444)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tabs = listOf(
                "连接记录 (${connections.size})",
                "实时日志 (${logs.size})"
            )
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSelected) VergePrimary else Color.Transparent
                        )
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }
                        .clickable { selectedTab = index }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    text = if (selectedTab == 0) "搜索域名、应用、IP、规则或出站节点..." else "搜索日志关键字、域名或规则...",
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        if (selectedTab == 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "内核日志:",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                SettingsRepository.VALID_LOG_LEVELS.forEach { level ->
                    val active = currentLogLevel.equals(level, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) VergePrimary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (active) VergePrimary.copy(alpha = 0.45f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onLogLevelChange(level) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = level.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) VergePrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val levels = listOf(
                    "ALL" to "全部",
                    "INFO" to "INFO",
                    "DEBUG" to "DEBUG",
                    "WARN" to "WARN",
                    "ERROR" to "ERROR"
                )
                levels.forEach { (key, label) ->
                    val active = selectedLogLevel == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) VergePrimary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (active) VergePrimary.copy(alpha = 0.45f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .semantics {
                                role = Role.Tab
                                selected = active
                            }
                            .clickable { selectedLogLevel = key }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) VergePrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedTab == 0) {
            if (filteredConnections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isEmpty()) "暂无连接记录（访问网页后将实时显示）" else "未匹配到相关连接",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            } else {
                val activeDown = filteredConnections.sumOf { it.first.download }
                val activeUp = filteredConnections.sumOf { it.first.upload }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前 ${filteredConnections.size} 条 · 长按复制",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "↓ ${TrafficStats.formatBytes(activeDown)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TrafficDownload
                        )
                        Text(
                            text = "↑ ${TrafficStats.formatBytes(activeUp)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TrafficUpload
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredConnections, key = { it.first.id }) { (item, processLabel) ->
                        ConnectionItemRow(
                            item = item,
                            processLabel = processLabel,
                            onLongClick = {
                                val target = item.metadata.host.ifEmpty { item.metadata.destinationIP }
                                val addr = if (item.metadata.destinationPort.isNotEmpty()) {
                                    "$target:${item.metadata.destinationPort}"
                                } else {
                                    target
                                }
                                val outbound = item.chains.lastOrNull() ?: "DIRECT"
                                val summary = buildString {
                                    append(addr)
                                    append(" | ")
                                    append(item.metadata.network.uppercase())
                                    if (processLabel.isNotBlank()) {
                                        append(" | ")
                                        append(processLabel)
                                    }
                                    append(" | 规则: ")
                                    append(item.rule)
                                    append(" -> ")
                                    append(outbound)
                                }
                                copyToClipboard(context, "connection_info", summary, "已复制连接信息")
                            },
                            onClose = { onCloseConnection(item.id) }
                        )
                    }
                }
            }
        } else {
            if (isLogPaused) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.14f))
                        .clickable { isLogPaused = false }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "日志流已暂停 (${displayedLogs.size} 条快照 · 长按日志可复制)",
                        fontSize = 11.sp,
                        color = Color(0xFFD97706),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "点击恢复",
                        fontSize = 11.sp,
                        color = VergePrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isEmpty()) "暂无内核日志记录" else "未匹配到相关日志",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        LogEntryRow(
                            log = log,
                            onLongClick = {
                                val text = "[${log.timeText}] [${log.type.uppercase()}] ${log.payload}"
                                copyToClipboard(context, "kernel_log", text, "已复制日志内容")
                            }
                        )
                    }
                }
            }
        }
    }
}
