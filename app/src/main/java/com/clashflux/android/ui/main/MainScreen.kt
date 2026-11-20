package com.clashflux.android.ui.main

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.theme.VergePrimary
import com.clashflux.android.ui.connections.ConnectionsScreen
import com.clashflux.android.ui.dashboard.DashboardScreen
import com.clashflux.android.ui.profiles.ProfilesScreen
import com.clashflux.android.ui.proxies.ProxiesScreen
import com.clashflux.android.ui.settings.PerAppProxyScreen
import com.clashflux.android.ui.settings.SettingsScreen

enum class VergeTab(val label: String, val icon: ImageVector) {
    Dashboard("仪表盘", Icons.Default.Dashboard),
    Proxies("代理", Icons.Default.Public),
    Profiles("订阅", Icons.Default.Dns),
    Connections("连接", Icons.Default.Sensors),
    Settings("设置", Icons.Default.Settings)
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    var currentTab by rememberSaveable { mutableStateOf(VergeTab.Dashboard) }
    var inPerAppProxyScreen by rememberSaveable { mutableStateOf(false) }

    val status by viewModel.status.collectAsStateWithLifecycle()
    val speed by viewModel.currentSpeed.collectAsStateWithLifecycle()
    val sessionTraffic by viewModel.sessionTraffic.collectAsStateWithLifecycle()
    val totalTraffic by viewModel.totalTraffic.collectAsStateWithLifecycle()
    val connectedDurationSeconds by viewModel.connectedDurationSeconds.collectAsStateWithLifecycle()
    val speedHistory by viewModel.speedHistory.collectAsStateWithLifecycle()
    val mode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val groups by viewModel.proxyGroups.collectAsStateWithLifecycle()
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val busyProfileIds by viewModel.busyProfileIds.collectAsStateWithLifecycle()
    val isAddingProfile by viewModel.isAddingProfile.collectAsStateWithLifecycle()
    val isUpdatingGeo by viewModel.isUpdatingGeo.collectAsStateWithLifecycle()
    val appliedRouteSnapshot by viewModel.appliedRouteSnapshot.collectAsStateWithLifecycle()
    val hasPendingVpnRestart by viewModel.hasPendingVpnRestart.collectAsStateWithLifecycle()

    val currentProfileName = profiles.find { it.isSelected }?.name ?: ""
    val primaryGroup = com.clashflux.android.core.YamlProxyGroupParser.selectPrimaryGroup(groups)
    val currentNodeName = primaryGroup?.now ?: ""
    val currentNodeDelay = primaryGroup?.all?.find { it.name == currentNodeName }?.delay ?: -1

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, _ ->
            isLifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setConnectionsMonitoringActive(false)
        }
    }
    LaunchedEffect(isLifecycleStarted, currentTab, inPerAppProxyScreen) {
        val active = isLifecycleStarted && !inPerAppProxyScreen &&
            (currentTab == VergeTab.Dashboard || currentTab == VergeTab.Connections)
        viewModel.setConnectionsMonitoringActive(active)
    }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnService()
        } else {
            viewModel.cancelConnecting()
        }
    }

    val startWithVpnPermissionCheck = {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            viewModel.startVpnService()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Proceed regardless: VPN runs cleanly even if notification is denied, though status notification may be hidden
        startWithVpnPermissionCheck()
    }

    val handleToggleConnection = {
        when (status) {
            ConnectionStatus.Connected, ConnectionStatus.Connecting -> viewModel.stopVpnService()
            ConnectionStatus.Disconnected -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startWithVpnPermissionCheck()
                }
            }
            ConnectionStatus.Disconnecting -> Unit
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (!inPerAppProxyScreen) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    VergeTab.entries.forEach { tab ->
                        val isSelected = currentTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = VergePrimary,
                                selectedTextColor = VergePrimary,
                                indicatorColor = VergePrimary.copy(alpha = 0.15f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (inPerAppProxyScreen) {
                PerAppProxyScreen(
                    settings = settings,
                    isVpnRunning = status == ConnectionStatus.Connected,
                    appliedPerAppMode = appliedRouteSnapshot?.perAppMode,
                    appliedPerAppPackages = appliedRouteSnapshot?.perAppPackages,
                    onModeChange = { viewModel.setPerAppMode(it) },
                    onTogglePackage = { viewModel.togglePerAppPackage(it) },
                    onSetPackages = { viewModel.setPerAppPackages(it) },
                    onHideSystemAppsChange = { viewModel.setHideSystemApps(it) },
                    onRestartVpn = { viewModel.restartVpnIfRunning() },
                    onNavigateBack = { inPerAppProxyScreen = false }
                )
            } else {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tabTransition"
                ) { tab ->
                    when (tab) {
                        VergeTab.Dashboard -> DashboardScreen(
                            status = status,
                            currentSpeed = speed,
                            speedHistory = speedHistory,
                            selectedMode = mode,
                            currentProfileName = currentProfileName,
                            currentNodeName = currentNodeName,
                            onToggleConnection = handleToggleConnection,
                            onModeSelected = { viewModel.setProxyMode(it) },
                            onNavigateToProxies = { currentTab = VergeTab.Proxies },
                            onNavigateToProfiles = { currentTab = VergeTab.Profiles },
                            sessionTraffic = sessionTraffic,
                            totalTraffic = totalTraffic,
                            connectedDurationSeconds = connectedDurationSeconds,
                            currentNodeDelay = currentNodeDelay
                        )

                        VergeTab.Proxies -> ProxiesScreen(
                            groups = groups,
                            onSelectNode = { groupName, nodeName ->
                                viewModel.selectNode(groupName, nodeName)
                            },
                            onTestGroupDelay = { groupName ->
                                viewModel.testGroupDelay(groupName)
                            },
                            onTestNodeDelay = { groupName, nodeName ->
                                viewModel.testNodeDelay(groupName, nodeName)
                            },
                            currentProfileName = currentProfileName,
                            activeProfileId = profiles.find { it.isSelected }?.id,
                            loadProfileRulesData = { viewModel.loadProfileRulesData(it) },
                            onSaveProfileRules = { id, rules -> viewModel.saveProfileRules(id, rules) }
                        )

                        VergeTab.Profiles -> ProfilesScreen(
                            profiles = profiles,
                            onSelectProfile = { viewModel.selectProfile(it) },
                            onUpdateProfile = { viewModel.updateProfile(it) },
                            onDeleteProfile = { viewModel.deleteProfile(it) },
                            onAddProfile = { name, url -> viewModel.addProfile(name, url) },
                            onImportLocalProfile = { name, yaml -> viewModel.importLocalProfile(name, yaml) },
                            onUpdateAllProfiles = { viewModel.updateAllRemoteProfiles() },
                            busyProfileIds = busyProfileIds,
                            isAddingProfile = isAddingProfile,
                            onEditProfileInfo = { id, name, url -> viewModel.editProfileInfo(id, name, url) },
                            loadProfileYaml = { viewModel.loadProfileYaml(it) },
                            onSaveProfileYaml = { id, content -> viewModel.saveProfileYaml(id, content) },
                            hasProfileBackup = { viewModel.hasProfileBackup(it) },
                            onRestoreProfileBackup = { viewModel.restoreProfileBackup(it) },
                            loadProfileRulesData = { viewModel.loadProfileRulesData(it) },
                            onSaveProfileRules = { id, rules -> viewModel.saveProfileRules(id, rules) },
                            loadProfileProxies = { viewModel.loadProfileProxies(it) },
                            onSaveProfileProxies = { id, proxiesList -> viewModel.saveProfileProxies(id, proxiesList) }
                        )

                        VergeTab.Connections -> ConnectionsScreen(
                            connections = connections,
                            onCloseAll = { viewModel.closeAllConnections() },
                            onCloseConnection = { viewModel.closeConnection(it) },
                            logs = logs,
                            currentLogLevel = settings.logLevel,
                            onLogLevelChange = { viewModel.setLogLevel(it) },
                            onClearLogs = { viewModel.clearLogs() }
                        )

                        VergeTab.Settings -> SettingsScreen(
                            settings = settings,
                            onNavigateToPerAppProxy = { inPerAppProxyScreen = true },
                            isVpnRunning = status == ConnectionStatus.Connected,
                            hasPendingVpnRestart = hasPendingVpnRestart,
                            isUpdatingGeo = isUpdatingGeo,
                            onStartOnBootChange = { viewModel.setStartOnBoot(it) },
                            onBypassLanChange = { viewModel.setBypassLan(it) },
                            onAutoUpdateProfilesChange = { viewModel.setAutoUpdateProfiles(it) },
                            onAllowLanChange = { viewModel.setAllowLan(it) },
                            onIpv6Change = { viewModel.setIpv6Enabled(it) },
                            onLogLevelChange = { viewModel.setLogLevel(it) },
                            onSaveProxyPorts = { mixed, http, socks ->
                                viewModel.setProxyPorts(mixed, http, socks)
                            },
                            onUpdateGeoDatabase = { viewModel.updateGeoDatabase() },
                            onThemeModeChange = { viewModel.setThemeMode(it) },
                            onDynamicColorChange = { viewModel.setDynamicColor(it) },
                            onRestartVpn = { viewModel.restartVpnIfRunning() }
                        )
                    }
                }
            }
        }
    }
}
