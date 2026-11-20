package com.clashflux.android.ui.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.theme.VergeAccent
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.theme.VergePrimaryDark

@Composable
private fun rememberHeroPulse(active: Boolean): Pair<Float, Float> {
    if (!active) return 1.0f to 0.14f
    val infiniteTransition = rememberInfiniteTransition(label = "heroPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    return pulseScale to pulseAlpha
}

@Composable
internal fun HeroConnectionCard(
    status: ConnectionStatus,
    isConnected: Boolean,
    isConnecting: Boolean,
    currentProfileName: String,
    currentNodeName: String,
    connectedDurationSeconds: Long,
    onToggleConnection: () -> Unit,
    onNavigateToProxies: () -> Unit
) {
    val (pulseScale, pulseAlpha) = rememberHeroPulse(active = isConnected || isConnecting)

    val cardShape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isConnected) 14.dp else 3.dp,
                shape = cardShape,
                ambientColor = if (isConnected) VergePrimary else Color.Black.copy(alpha = 0.08f),
                spotColor = if (isConnected) VergePrimary else Color.Black.copy(alpha = 0.08f)
            )
            .clip(cardShape)
            .background(
                if (isConnected) {
                    Brush.linearGradient(
                        colors = listOf(
                            VergePrimaryDark,
                            VergePrimary,
                            VergeAccent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(900f, 500f)
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                }
            )
            .border(
                width = 1.dp,
                color = if (isConnected) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                shape = cardShape
            )
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val circleColor = if (isConnected) {
                Color.White.copy(alpha = 0.07f)
            } else {
                VergePrimary.copy(alpha = 0.04f)
            }
            drawCircle(
                color = circleColor,
                radius = size.height * 0.85f,
                center = Offset(size.width * 0.88f, size.height * 0.2f)
            )
            drawCircle(
                color = circleColor,
                radius = size.height * 0.55f,
                center = Offset(size.width * 0.75f, size.height * 0.95f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = when (status) {
                        ConnectionStatus.Connected -> "已连接"
                        ConnectionStatus.Connecting -> "正在连接..."
                        ConnectionStatus.Disconnecting -> "正在断开..."
                        ConnectionStatus.Disconnected -> "点击开启代理"
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isConnected) Color.White else MaterialTheme.colorScheme.onSurface,
                    letterSpacing = (-0.3).sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (isConnected) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formatDuration(connectedDurationSeconds),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        if (currentNodeName.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .clickable { onNavigateToProxies() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = currentNodeName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (currentNodeName.isNotEmpty()) {
                            "出站: $currentNodeName (${currentProfileName.ifEmpty { "默认" }})"
                        } else {
                            "轻触右侧按钮立即启动代理"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(86.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) Color.White.copy(alpha = pulseAlpha)
                            else VergePrimary.copy(alpha = pulseAlpha)
                        )
                )

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(
                            elevation = if (isConnected) 8.dp else 6.dp,
                            shape = CircleShape,
                            ambientColor = if (isConnected) Color.Black.copy(alpha = 0.25f) else VergePrimary,
                            spotColor = if (isConnected) Color.Black.copy(alpha = 0.25f) else VergePrimary
                        )
                        .clip(CircleShape)
                        .background(
                            if (isConnected) {
                                Brush.linearGradient(listOf(Color.White, Color(0xFFF0F4FF)))
                            } else {
                                Brush.linearGradient(listOf(VergePrimary, VergeAccent))
                            }
                        )
                        .border(
                            width = 2.dp,
                            color = if (isConnected) Color.White else Color.White.copy(alpha = 0.25f),
                            shape = CircleShape
                        )
                        .clickable { onToggleConnection() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Toggle Connection",
                        tint = if (isConnected) VergePrimaryDark else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
