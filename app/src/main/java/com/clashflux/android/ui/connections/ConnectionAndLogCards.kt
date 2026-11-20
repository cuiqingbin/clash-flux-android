package com.clashflux.android.ui.connections

import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.model.ConnectionItem
import com.clashflux.android.model.ConnectionMetadata
import com.clashflux.android.model.LogEntry
import com.clashflux.android.model.TrafficStats
import com.clashflux.android.theme.TrafficDownload
import com.clashflux.android.theme.TrafficUpload
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.ui.components.VergeCard

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConnectionItemRow(
    item: ConnectionItem,
    processLabel: String,
    onLongClick: () -> Unit,
    onClose: () -> Unit
) {
    val host = item.metadata.host.ifEmpty { item.metadata.destinationIP }
    val port = item.metadata.destinationPort
    val chain = item.chains.lastOrNull() ?: "DIRECT"
    val isReject = chain.equals("REJECT", ignoreCase = true)
    val isDirect = chain.equals("DIRECT", ignoreCase = true)
    val chainColor = when {
        isReject -> Color(0xFFEF4444)
        isDirect -> TrafficDownload
        else -> VergePrimary
    }
    val netColor = if (item.metadata.network.equals("UDP", ignoreCase = true)) {
        Color(0xFF8B5CF6)
    } else {
        VergePrimary
    }

    VergeCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick
            ),
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(netColor.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = item.metadata.network.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = netColor
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(chainColor.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    isReject -> "拦截"
                                    isDirect -> "直连"
                                    else -> "代理"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = chainColor
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = if (port.isNotEmpty()) "$host:$port" else host,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (item.start.isNotEmpty()) {
                        Text(
                            text = item.start,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(5.dp))

                val middleText = buildString {
                    if (processLabel.isNotBlank()) {
                        append(processLabel)
                        append(" · ")
                    }
                    append("规则: ")
                    append(item.rule)
                    append(" ➔ ")
                    append(chain)
                }
                Text(
                    text = middleText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val srcText = if (item.metadata.sourceIP.isNotEmpty()) {
                        "来源: ${item.metadata.sourceIP}:${item.metadata.sourcePort}"
                    } else {
                        "TUN 虚拟网卡分流"
                    }
                    Text(
                        text = srcText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "↓ ${TrafficStats.formatBytes(item.download)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TrafficDownload,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "↑ ${TrafficStats.formatBytes(item.upload)}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TrafficUpload,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LogEntryRow(
    log: LogEntry,
    onLongClick: () -> Unit
) {
    val levelColor = when (log.type.uppercase()) {
        "ERROR" -> Color(0xFFEF4444)
        "WARN", "WARNING" -> Color(0xFFF59E0B)
        "INFO" -> Color(0xFF10B981)
        else -> Color(0xFF6366F1)
    }

    VergeCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick
            ),
        contentPadding = PaddingValues(11.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(levelColor.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = log.type.uppercase(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = levelColor
                    )
                }

                Text(
                    text = log.timeText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
                )
            }

            Text(
                text = log.payload,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                lineHeight = 16.sp
            )
        }
    }
}

internal fun lookupAppLabelForUid(pm: PackageManager, uid: Int): String {
    if (uid <= 0) return ""
    return runCatching {
        val pkgName = pm.getPackagesForUid(uid)?.firstOrNull()
        if (!pkgName.isNullOrBlank()) {
            val info = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(info).toString()
        } else {
            ""
        }
    }.getOrDefault("")
}

internal fun formatProcessLabel(
    metadata: ConnectionMetadata,
    uidAppLabel: String
): String {
    val processName = metadata.process.ifBlank {
        metadata.processPath.substringAfterLast('/')
    }
    return when {
        uidAppLabel.isNotBlank() && processName.isNotBlank() && !uidAppLabel.equals(processName, ignoreCase = true) ->
            "$uidAppLabel ($processName)"
        uidAppLabel.isNotBlank() -> uidAppLabel
        else -> processName
    }
}
