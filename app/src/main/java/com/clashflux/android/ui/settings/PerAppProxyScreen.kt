package com.clashflux.android.ui.settings

import android.content.pm.ApplicationInfo
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.PerAppMode
import com.clashflux.android.model.AppInfo
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.theme.clashFluxSwitchColors
import com.clashflux.android.ui.components.VergeCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PerAppProxyScreen(
    settings: AppSettings,
    onModeChange: (PerAppMode) -> Unit,
    onTogglePackage: (String) -> Unit,
    onSetPackages: (Set<String>) -> Unit,
    onHideSystemAppsChange: (Boolean) -> Unit,
    onRestartVpn: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    isVpnRunning: Boolean = false,
    appliedPerAppMode: PerAppMode? = null,
    appliedPerAppPackages: Set<String>? = null
) {
    val context = LocalContext.current
    var localAppliedMode by remember { mutableStateOf(appliedPerAppMode ?: settings.perAppMode) }
    var localAppliedPackages by remember { mutableStateOf(appliedPerAppPackages ?: settings.perAppPackages) }
    val baselineMode = appliedPerAppMode ?: localAppliedMode
    val baselinePackages = appliedPerAppPackages ?: localAppliedPackages
    val markRulesApplied = {
        localAppliedMode = settings.perAppMode
        localAppliedPackages = settings.perAppPackages
    }
    LaunchedEffect(isVpnRunning) {
        if (!isVpnRunning) {
            markRulesApplied()
        }
    }
    val triggerVpnRestart = {
        markRulesApplied()
        onRestartVpn()
    }
    val hasPendingVpnChanges = isVpnRunning &&
        (settings.perAppMode != baselineMode || settings.perAppPackages != baselinePackages)
    var showReconnectDialog by rememberSaveable { mutableStateOf(false) }

    val handleBackNavigation = {
        if (hasPendingVpnChanges) {
            showReconnectDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler(onBack = handleBackNavigation)

    val iconCache = remember { LruCache<String, ImageBitmap>(120) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val selfPkg = context.packageName
                pm.getInstalledPackages(0).mapNotNull { pkg ->
                    val ai = pkg.applicationInfo ?: return@mapNotNull null
                    if (pkg.packageName == selfPkg) return@mapNotNull null
                    val name = pm.getApplicationLabel(ai).toString()
                    val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    AppInfo(
                        packageName = pkg.packageName,
                        appName = name,
                        isSystemApp = isSystem
                    )
                }.sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
            } catch (_: Exception) {
                emptyList()
            }
        }
        apps = loaded
        isLoading = false
    }

    val filteredApps = remember(apps, searchQuery, settings.hideSystemApps) {
        val query = searchQuery.trim()
        apps.filter { app ->
            val systemOk = !settings.hideSystemApps || !app.isSystemApp
            val queryOk = query.isEmpty() ||
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            systemOk && queryOk
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = handleBackNavigation) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "分应用代理",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isLoading) {
                        "正在扫描已安装应用..."
                    } else {
                        "已选 ${settings.perAppPackages.size} 个应用 · 显示 ${filteredApps.size} 个"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            if (isVpnRunning) {
                TextButton(onClick = triggerVpnRestart) {
                    Text("重连生效", fontSize = 12.sp, color = VergePrimary, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (hasPendingVpnChanges) {
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
                    text = "分应用规则已修改，重连 VPN 后对隧道生效",
                    fontSize = 12.sp,
                    color = Color(0xFFB45309),
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = triggerVpnRestart,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("立即重连", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VergePrimary)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Mode selector: All / Blacklist / Whitelist
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                PerAppMode.ALL to "全部代理",
                PerAppMode.BYPASS_SELECTED to "黑名单直连",
                PerAppMode.PROXY_SELECTED to "白名单代理"
            ).forEach { (mode, label) ->
                val selected = settings.perAppMode == mode
                FilterChip(
                    selected = selected,
                    onClick = { onModeChange(mode) },
                    label = { Text(label, fontSize = 12.sp) },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = VergePrimary.copy(alpha = 0.16f),
                        selectedLabelColor = VergePrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索应用名称或包名") },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") },
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

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "隐藏系统应用",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = settings.hideSystemApps,
                    onCheckedChange = onHideSystemAppsChange,
                    colors = clashFluxSwitchColors()
                )
            }

            if (settings.perAppMode != PerAppMode.ALL) {
                Row {
                    TextButton(onClick = {
                        val visiblePkgs = filteredApps.map { it.packageName }.toSet()
                        onSetPackages(settings.perAppPackages + visiblePkgs)
                    }) {
                        Text("全选当前", fontSize = 12.sp, color = VergePrimary)
                    }
                    TextButton(onClick = {
                        onSetPackages(emptySet())
                    }) {
                        Text(
                            "清空已选",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = VergePrimary)
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isEmpty()) "未检测到已安装应用" else "未匹配到相关应用",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = when (settings.perAppMode) {
                        PerAppMode.ALL -> true
                        PerAppMode.BYPASS_SELECTED, PerAppMode.PROXY_SELECTED ->
                            app.packageName in settings.perAppPackages
                    }
                    VergeCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIconThumbnail(
                                packageName = app.packageName,
                                iconCache = iconCache
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = app.appName,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (app.isSystemApp) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(text = "系统", fontSize = 9.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = app.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Checkbox(
                                checked = isChecked,
                                enabled = settings.perAppMode != PerAppMode.ALL,
                                onCheckedChange = {
                                    onTogglePackage(app.packageName)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = VergePrimary)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showReconnectDialog) {
        AlertDialog(
            onDismissRequest = { showReconnectDialog = false },
            title = { Text("应用分应用代理变更", fontWeight = FontWeight.Bold) },
            text = { Text("当前 VPN 隧道正在运行，是否立即重连 VPN 使新的分应用规则生效？") },
            confirmButton = {
                Button(
                    onClick = {
                        showReconnectDialog = false
                        markRulesApplied()
                        onRestartVpn()
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VergePrimary)
                ) {
                    Text("重连并退出", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showReconnectDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("稍后手动重连")
                }
            }
        )
    }
}

@Composable
private fun AppIconThumbnail(
    packageName: String,
    iconCache: LruCache<String, ImageBitmap>
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = iconCache.get(packageName), key1 = packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(packageName)
                    drawable.toBitmap(width = 72, height = 72).asImageBitmap()
                }.getOrNull()?.also { loaded ->
                    iconCache.put(packageName, loaded)
                }
            }
        }
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
