package com.clashflux.android.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.clashflux.android.BuildConfig
import com.clashflux.android.core.MihomoControllerClient
import com.clashflux.android.core.MihomoCoreManager
import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.PerAppMode
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.data.ThemeMode
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.ui.components.VergeCard

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onNavigateToPerAppProxy: () -> Unit,
    modifier: Modifier = Modifier,
    isVpnRunning: Boolean = false,
    hasPendingVpnRestart: Boolean = false,
    isUpdatingGeo: Boolean = false,
    onStartOnBootChange: (Boolean) -> Unit = {},
    onBypassLanChange: (Boolean) -> Unit = {},
    onAutoUpdateProfilesChange: (Boolean) -> Unit = {},
    onAllowLanChange: (Boolean) -> Unit = {},
    onIpv6Change: (Boolean) -> Unit = {},
    onLogLevelChange: (String) -> Unit = {},
    onSaveProxyPorts: suspend (mixedPort: Int, httpPort: Int, socksPort: Int) -> Result<Unit> = { _, _, _ ->
        Result.success(Unit)
    },
    onUpdateGeoDatabase: () -> Unit = {},
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    onRestartVpn: () -> Unit = {}
) {
    val coreVersion = remember { MihomoCoreManager.getCoreVersion() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var showPortsDialog by remember { mutableStateOf(false) }

    val perAppSummary = when (settings.perAppMode) {
        PerAppMode.ALL -> "当前模式: 全部应用走代理"
        PerAppMode.BYPASS_SELECTED -> "黑名单模式: 已排除 ${settings.perAppPackages.size} 个应用"
        PerAppMode.PROXY_SELECTED -> "白名单模式: 仅代理 ${settings.perAppPackages.size} 个应用"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "系统设置",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "网络、分应用代理与系统集成",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        // Section: Android Features
        if (isVpnRunning && hasPendingVpnRestart) {
            VergeCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "路由配置待重启生效",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "分应用代理、IPv6 或绕过局域网设置已更改，请重连 VPN 使新路由生效",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onRestartVpn,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VergePrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("立即重连", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        Text(
            text = "移动端特化功能",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = VergePrimary
        )

        VergeCard(modifier = Modifier.fillMaxWidth()) {
            SettingNavigationItem(
                icon = Icons.Default.Apps,
                title = "分应用代理",
                subtitle = perAppSummary,
                onClick = onNavigateToPerAppProxy
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSwitchItem(
                icon = Icons.Default.BatteryChargingFull,
                title = "开机自启连接",
                subtitle = "设备重启完成后自动拉起 VPN 服务（需已授权 VPN）",
                checked = settings.startOnBoot,
                onCheckedChange = onStartOnBootChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSwitchItem(
                icon = Icons.Default.Router,
                title = "绕过局域网与私有网络",
                subtitle = if (isVpnRunning) "局域网流量直接直连（变更路由后重连生效）" else "局域网及私有 IP 流量直接直连",
                checked = settings.bypassLan,
                onCheckedChange = onBypassLanChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSwitchItem(
                icon = Icons.Default.Autorenew,
                title = "启动时自动更新订阅",
                subtitle = "应用启动且距离上次更新超过 6 小时时自动同步远程订阅",
                checked = settings.autoUpdateProfiles,
                onCheckedChange = onAutoUpdateProfilesChange
            )
        }

        // Section: Core Networking
        Text(
            text = "内核网络参数",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = VergePrimary
        )

        VergeCard(modifier = Modifier.fillMaxWidth()) {
            SettingClickableInfoItem(
                title = "混合端口 (Mixed Port)",
                value = "${settings.mixedPort} (点击修改)",
                onClick = { showPortsDialog = true }
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingClickableInfoItem(
                title = "HTTP / SOCKS5 端口",
                value = "${settings.httpPort} / ${settings.socksPort}",
                onClick = { showPortsDialog = true }
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingInfoItem(
                title = "控制端口 (Controller)",
                value = MihomoControllerClient.CONTROLLER_PORT.toString()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isUpdatingGeo) { onUpdateGeoDatabase() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = VergePrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Column {
                        Text(
                            text = "更新 GeoIP / GeoSite 数据库",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isUpdatingGeo) "正在后台更新规则数据库..." else "通过内核控制器拉取最新地理分流库",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                if (isUpdatingGeo) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = VergePrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            SettingSwitchItem(
                icon = Icons.Default.Tune,
                title = "允许局域网共享 (Allow LAN)",
                subtitle = "允许局域网设备连接本机的 HTTP/SOCKS 代理",
                checked = settings.allowLan,
                onCheckedChange = onAllowLanChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSwitchItem(
                icon = Icons.Default.Tune,
                title = "IPv6 开关",
                subtitle = "启用 Mihomo 内核与 TUN 隧道的 IPv6 流量分流",
                checked = settings.ipv6Enabled,
                onCheckedChange = onIpv6Change
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingDropdownItem(
                icon = Icons.Default.Terminal,
                title = "内核日志等级",
                subtitle = "控制 Mihomo 内核输出日志的详细程度",
                selectedValue = settings.logLevel,
                options = SettingsRepository.VALID_LOG_LEVELS,
                onOptionSelected = onLogLevelChange,
                formatLabel = { it.uppercase() }
            )
        }

        // Section: Appearance
        Text(
            text = "界面与外观",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = VergePrimary
        )

        VergeCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "主题模式",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    val selected = settings.themeMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onThemeModeChange(mode) },
                        label = { Text(mode.label, fontSize = 12.sp) },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VergePrimary.copy(alpha = 0.16f),
                            selectedLabelColor = VergePrimary
                        )
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingSwitchItem(
                    icon = Icons.Default.Palette,
                    title = "动态取色 (Material You)",
                    subtitle = "跟随系统壁纸自动生成界面配色",
                    checked = settings.dynamicColor,
                    onCheckedChange = onDynamicColorChange
                )
            }
        }

        // Section: About
        Text(
            text = "关于",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = VergePrimary
        )

        VergeCard(modifier = Modifier.fillMaxWidth()) {
            SettingInfoItem(title = "客户端版本", value = "ClashFlux Android ${BuildConfig.VERSION_NAME}")
            Spacer(modifier = Modifier.height(8.dp))
            SettingInfoItem(title = "内核版本", value = coreVersion)
            Spacer(modifier = Modifier.height(8.dp))
            SettingInfoItem(title = "内核架构", value = "Mihomo + Jetpack Compose")
        }
    }

    if (showPortsDialog) {
        EditProxyPortsDialog(
            initialMixed = settings.mixedPort,
            initialHttp = settings.httpPort,
            initialSocks = settings.socksPort,
            onDismiss = { showPortsDialog = false },
            onSave = { mixed, http, socks ->
                scope.launch {
                    val res = onSaveProxyPorts(mixed, http, socks)
                    if (res.isSuccess) {
                        showPortsDialog = false
                    }
                }
            }
        )
    }
}
