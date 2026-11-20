package com.clashflux.android.ui.dashboard

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.ProxyMode
import com.clashflux.android.model.TrafficStats
import com.clashflux.android.theme.LatencyGood
import com.clashflux.android.theme.LatencyMedium
import com.clashflux.android.ui.components.SegmentedModeSelector
import com.clashflux.android.ui.components.VergeCard

@Composable
fun DashboardScreen(
    status: ConnectionStatus,
    currentSpeed: TrafficStats,
    speedHistory: List<TrafficStats>,
    selectedMode: ProxyMode,
    currentProfileName: String,
    currentNodeName: String,
    onToggleConnection: () -> Unit,
    onModeSelected: (ProxyMode) -> Unit,
    onNavigateToProxies: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    sessionTraffic: TrafficStats = TrafficStats(0, 0),
    totalTraffic: TrafficStats = TrafficStats(0, 0),
    connectedDurationSeconds: Long = 0L,
    currentNodeDelay: Int = -1
) {
    val scrollState = rememberScrollState()
    val isConnected = status == ConnectionStatus.Connected
    val isConnecting = status == ConnectionStatus.Connecting || status == ConnectionStatus.Disconnecting

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeroConnectionCard(
            status = status,
            isConnected = isConnected,
            isConnecting = isConnecting,
            currentProfileName = currentProfileName,
            currentNodeName = currentNodeName,
            connectedDurationSeconds = connectedDurationSeconds,
            onToggleConnection = onToggleConnection,
            onNavigateToProxies = onNavigateToProxies
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "代理转发模式",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val modeDesc = when (selectedMode) {
                    ProxyMode.Rule -> "按规则智能分流国内外流量"
                    ProxyMode.Global -> "全部流量通过选定节点代理"
                    ProxyMode.Direct -> "全部流量直接连接不走代理"
                }
                Text(
                    text = modeDesc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            SegmentedModeSelector(
                selectedMode = selectedMode,
                onModeSelected = onModeSelected
            )
        }

        TrafficTelemetryCard(
            currentSpeed = currentSpeed,
            sessionTraffic = sessionTraffic,
            totalTraffic = totalTraffic,
            speedHistory = speedHistory
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardShortcutCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Dns,
                accentColor = Color(0xFF3B82F6),
                label = "当前配置订阅",
                value = currentProfileName.ifEmpty { "未选择配置" },
                actionHint = "点击管理配置与分流规则",
                onClick = onNavigateToProfiles
            )

            DashboardShortcutCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Public,
                accentColor = Color(0xFF10B981),
                label = "当前出站节点",
                value = currentNodeName.ifEmpty { "未配置节点" },
                actionHint = "点击切换节点或延迟测速",
                onClick = onNavigateToProxies,
                trailingContent = {
                    if (currentNodeDelay > 0) {
                        val badgeColor = if (currentNodeDelay < 260) LatencyGood else LatencyMedium
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(badgeColor.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "${currentNodeDelay}ms",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = badgeColor
                            )
                        }
                    } else {
                        ShortcutChevronIcon()
                    }
                }
            )
        }
    }
}

@Composable
private fun DashboardShortcutCard(
    icon: ImageVector,
    accentColor: Color,
    label: String,
    value: String,
    actionHint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = { ShortcutChevronIcon() }
) {
    VergeCard(
        modifier = modifier.clickable(onClick = onClick),
        contentPadding = PaddingValues(15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(19.dp)
                )
            }

            trailingContent()
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = actionHint,
            fontSize = 10.sp,
            color = accentColor.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ShortcutChevronIcon() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
    }
}
