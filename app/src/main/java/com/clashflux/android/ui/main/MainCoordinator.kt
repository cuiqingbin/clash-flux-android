package com.clashflux.android.ui.main

import com.clashflux.android.core.ClashConfigEditor
import com.clashflux.android.core.MihomoControllerClient
import com.clashflux.android.core.YamlProxyGroupParser
import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.PerAppMode
import com.clashflux.android.data.ProfileStore
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.data.SettingsStore
import com.clashflux.android.data.ThemeMode
import com.clashflux.android.data.VpnRouteSnapshot
import com.clashflux.android.model.ClashRule
import com.clashflux.android.model.ConnectionItem
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.LogEntry
import com.clashflux.android.model.Profile
import com.clashflux.android.model.ProxyGroup
import com.clashflux.android.model.ProxyMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Abstracts platform, JNI Bridge, REST controller, and VPN service side effects so that
 * [MainCoordinator] can be unit-tested on pure JVM without Robolectric or mocking frameworks.
 */
interface MainRuntimeEnvironment {
    val serviceState: StateFlow<ConnectionStatus>
    val appliedRouteSnapshot: StateFlow<VpnRouteSnapshot?>
    val serviceErrors: StateFlow<String?>
    val logEvents: Flow<LogEntry>
    val isVpnRunning: Boolean

    fun clearServiceError()
    fun ensureTotalTrafficLoaded()
    fun startVpn()
    fun stopVpn()
    fun restartVpn()

    suspend fun initCore()
    suspend fun loadCoreConfig(path: String): Boolean
    suspend fun applySettingsUpdate(settings: AppSettings)
    suspend fun updateGeoDatabase(): Boolean

    fun getSelectorChoice(groupName: String): String?
    fun putSelectorChoice(groupName: String, nodeName: String)
    fun patchNativeSelector(groupName: String, nodeName: String)

    suspend fun queryLiveProxies(): Map<String, MihomoControllerClient.LiveProxyInfo>?
    suspend fun queryNativeGroupNames(): List<String>
    suspend fun queryNativeGroupSnapshot(groupName: String): YamlProxyGroupParser.BridgeGroupSnapshot?
    suspend fun selectControllerProxy(groupName: String, nodeName: String): Boolean
    suspend fun testControllerGroupDelay(groupName: String): Map<String, Int>?
    suspend fun testControllerProxyDelay(proxyName: String): Int?
    suspend fun nativeHealthCheckGroup(groupName: String): Map<String, Int>
    suspend fun measureDirectHttpDelay(): Int
    suspend fun measureTcpConnectDelay(host: String, port: Int): Int

    suspend fun getLiveConnections(): List<ConnectionItem>?
    suspend fun closeControllerConnection(id: String): Boolean
    suspend fun closeAllControllerConnections(): Boolean
    suspend fun showToast(message: String, long: Boolean = false)
}

/**
 * Pure-Kotlin coordinator owning UI state flows, profile/settings mutations, delay probes,
 * and core synchronization for [MainViewModel].
 */
class MainCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val settingsStore: SettingsStore,
    private val profileStore: ProfileStore,
    private val env: MainRuntimeEnvironment,
    private val fileExists: (String) -> Boolean = { path -> File(path).let { it.exists() && it.length() > 0L } },
    private val readFileText: (String) -> String = { path -> File(path).readText() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS
) {

    val status: StateFlow<ConnectionStatus> = env.serviceState

    val settingsState: StateFlow<AppSettings> = settingsStore.settingsFlow
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val selectedMode: StateFlow<ProxyMode> = settingsState
        .map { it.proxyMode }
        .stateIn(scope, SharingStarted.Eagerly, ProxyMode.Rule)

    val hasPendingVpnRestart: StateFlow<Boolean> = combine(
        env.serviceState,
        env.appliedRouteSnapshot,
        settingsState
    ) { state, appliedSnapshot, currentSettings ->
        state == ConnectionStatus.Connected &&
            appliedSnapshot != null &&
            appliedSnapshot.differsFrom(currentSettings)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles = _profiles.asStateFlow()

    private val _proxyGroups = MutableStateFlow<List<ProxyGroup>>(emptyList())
    val proxyGroups = _proxyGroups.asStateFlow()

    private val nodeHostPortMap = ConcurrentHashMap<String, Pair<String, Int>>()

    private val _connections = MutableStateFlow<List<ConnectionItem>>(emptyList())
    val connections = _connections.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _isAddingProfile = MutableStateFlow(false)
    val isAddingProfile = _isAddingProfile.asStateFlow()

    private val _busyProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val busyProfileIds = _busyProfileIds.asStateFlow()

    private val _isUpdatingGeo = MutableStateFlow(false)
    val isUpdatingGeo = _isUpdatingGeo.asStateFlow()

    private val connectionsMonitoringActive = MutableStateFlow(false)
    private var connectWatchdogJob: Job? = null
    private var selectProfileJob: Job? = null
    private val selectProfileRequestSeq = AtomicLong(0L)
    private val delayCoordinator = ProxyDelayCoordinator(
        scope = scope,
        ioDispatcher = ioDispatcher,
        env = env,
        proxyGroups = _proxyGroups,
        nodeHostPortMap = nodeHostPortMap
    )

    fun start(autoLoadSavedProfiles: Boolean = true) {
        env.ensureTotalTrafficLoaded()

        scope.launch(ioDispatcher) {
            env.initCore()
        }

        scope.launch {
            env.serviceState.collect { state ->
                when (state) {
                    ConnectionStatus.Connected -> {
                        connectWatchdogJob?.cancel()
                        connectWatchdogJob = null
                        launch(ioDispatcher) {
                            syncLiveProxyGroupsFromCore()
                            delayCoordinator.warmUpPrimaryGroupDelays()
                        }
                    }
                    ConnectionStatus.Disconnected -> {
                        connectWatchdogJob?.cancel()
                        connectWatchdogJob = null
                        _connections.value = emptyList()
                    }
                    ConnectionStatus.Connecting -> {
                        connectWatchdogJob?.cancel()
                        connectWatchdogJob = launch {
                            delay(connectTimeoutMs)
                            if (env.serviceState.value == ConnectionStatus.Connecting) {
                                env.stopVpn()
                                env.showToast("连接超时，请检查配置或重试")
                            }
                        }
                    }
                    ConnectionStatus.Disconnecting -> {
                        connectWatchdogJob?.cancel()
                        connectWatchdogJob = null
                    }
                }
            }
        }

        scope.launch {
            env.serviceErrors.collect { err ->
                if (!err.isNullOrBlank()) {
                    env.showToast(err, long = true)
                    env.clearServiceError()
                }
            }
        }

        scope.launch {
            env.logEvents.collect { entry ->
                _logs.update { current -> (listOf(entry) + current).take(MAX_LOG_ENTRIES) }
            }
        }

        observeConnectionsPolling()
        if (autoLoadSavedProfiles) {
            loadSavedProfiles()
        }
    }

    fun setConnectionsMonitoringActive(active: Boolean) {
        connectionsMonitoringActive.value = active
    }

    private fun observeConnectionsPolling() {
        scope.launch(ioDispatcher) {
            combine(
                env.serviceState,
                connectionsMonitoringActive
            ) { state, monitoring ->
                state == ConnectionStatus.Connected && monitoring
            }.collectLatest { shouldPoll ->
                if (!shouldPoll) return@collectLatest
                while (isActive) {
                    env.getLiveConnections()?.let { liveList ->
                        _connections.value = liveList
                    }
                    delay(1000)
                }
            }
        }
    }

    fun loadSavedProfiles() {
        scope.launch(ioDispatcher) {
            val list = profileStore.loadProfiles()
            _profiles.value = list
            val selected = list.find { it.isSelected } ?: list.firstOrNull()
            if (selected != null) {
                if (fileExists(selected.filePath)) {
                    parseProxyGroupsFromYamlPath(selected.filePath)
                    if (env.serviceState.value == ConnectionStatus.Disconnected) {
                        env.loadCoreConfig(selected.filePath)
                        syncSelectorsToCore()
                    }
                    syncLiveProxyGroupsFromCore()
                    delayCoordinator.warmUpPrimaryGroupDelays()
                } else if (selected.url.isNotBlank()) {
                    updateProfile(selected.id)
                }
            }

            if (runCatching { settingsStore.getSnapshot().autoUpdateProfiles }.getOrDefault(false)) {
                autoUpdateStaleRemoteProfiles()
            }
        }
    }

    private suspend fun autoUpdateStaleRemoteProfiles() {
        val now = nowMillis()
        val staleRemoteIds = _profiles.value.filter { profile ->
            isRemoteUrl(profile.url) && (now - profile.updatedAt) >= AUTO_UPDATE_INTERVAL_MS
        }.map { it.id }

        if (staleRemoteIds.isEmpty()) return
        updateProfilesConcurrently(staleRemoteIds, silent = true)
    }

    private suspend fun updateProfilesConcurrently(
        profileIds: List<String>,
        silent: Boolean,
        maxConcurrency: Int = MAX_CONCURRENT_PROFILE_UPDATES
    ): List<Boolean> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        profileIds.map { profileId ->
            async {
                semaphore.withPermit {
                    updateProfileInternal(profileId, silent = silent)
                }
            }
        }.awaitAll()
    }

    fun startVpnService() {
        env.startVpn()
    }

    fun stopVpnService() {
        env.stopVpn()
    }

    fun restartVpnIfRunning() {
        if (!env.isVpnRunning) return
        env.restartVpn()
    }

    fun cancelConnecting() {
        if (env.serviceState.value == ConnectionStatus.Connecting) {
            env.stopVpn()
        }
    }

    fun toggleConnection() {
        when (status.value) {
            ConnectionStatus.Connected, ConnectionStatus.Connecting -> stopVpnService()
            ConnectionStatus.Disconnected -> startVpnService()
            ConnectionStatus.Disconnecting -> Unit
        }
    }

    // --- Settings & Modes ---

    fun setProxyMode(mode: ProxyMode) {
        scope.launch(ioDispatcher) {
            settingsStore.setProxyMode(mode)
            env.applySettingsUpdate(settingsStore.getSnapshot())
        }
    }

    fun setAllowLan(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settingsStore.setAllowLan(enabled)
            env.applySettingsUpdate(settingsStore.getSnapshot())
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settingsStore.setIpv6Enabled(enabled)
            env.applySettingsUpdate(settingsStore.getSnapshot())
            if (env.isVpnRunning) {
                env.showToast("IPv6 设置已保存，重连 VPN 后更新隧道路由")
            }
        }
    }

    fun setLogLevel(level: String) {
        val normalized = SettingsRepository.normalizeLogLevelSetting(level)
        scope.launch(ioDispatcher) {
            settingsStore.setLogLevel(normalized)
            env.applySettingsUpdate(settingsStore.getSnapshot())
        }
    }

    fun setStartOnBoot(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settingsStore.setStartOnBoot(enabled)
        }
    }

    fun setAutoUpdateProfiles(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settingsStore.setAutoUpdateProfiles(enabled)
        }
    }

    suspend fun setProxyPorts(mixedPort: Int, httpPort: Int, socksPort: Int): Result<Unit> {
        val invalid = listOf(mixedPort, httpPort, socksPort).any { it !in 1024..65535 }
        if (invalid) {
            val err = IllegalArgumentException("端口必须在 1024 到 65535 之间")
            env.showToast(err.message!!)
            return Result.failure(err)
        }
        if (setOf(mixedPort, httpPort, socksPort).size != 3) {
            val err = IllegalArgumentException("混合端口、HTTP 端口与 SOCKS5 端口不能重复")
            env.showToast(err.message!!)
            return Result.failure(err)
        }
        if (MihomoControllerClient.CONTROLLER_PORT in listOf(mixedPort, httpPort, socksPort)) {
            val err = IllegalArgumentException("代理端口不能与控制端口 (${MihomoControllerClient.CONTROLLER_PORT}) 冲突")
            env.showToast(err.message!!)
            return Result.failure(err)
        }

        withContext(ioDispatcher) {
            settingsStore.setProxyPorts(mixedPort, httpPort, socksPort)
            val updated = settingsStore.getSnapshot()
            env.applySettingsUpdate(updated)
        }
        env.showToast("代理端口已更新 ($mixedPort / $httpPort / $socksPort)")
        return Result.success(Unit)
    }

    fun setBypassLan(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settingsStore.setBypassLan(enabled)
            val selected = _profiles.value.find { it.isSelected }
            if (selected != null && fileExists(selected.filePath)) {
                val loaded = env.loadCoreConfig(selected.filePath)
                if (!loaded) {
                    env.showToast("规则重载失败，请检查当前配置", long = true)
                    return@launch
                }
                syncSelectorsToCore()
            }
            if (env.isVpnRunning) {
                env.showToast("绕过局域网规则已更新，重连 VPN 后更新系统路由")
            }
        }
    }

    fun setPerAppMode(mode: PerAppMode) {
        scope.launch(ioDispatcher) {
            settingsStore.setPerAppMode(mode)
        }
    }

    fun togglePerAppPackage(packageName: String) {
        scope.launch(ioDispatcher) {
            settingsStore.togglePerAppPackage(packageName)
        }
    }

    fun setPerAppPackages(packages: Set<String>) {
        scope.launch(ioDispatcher) {
            settingsStore.setPerAppPackages(packages)
        }
    }

    fun setHideSystemApps(hide: Boolean) {
        scope.launch(ioDispatcher) {
            settingsStore.setHideSystemApps(hide)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        scope.launch(ioDispatcher) {
            settingsStore.setThemeMode(mode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settingsStore.setDynamicColor(enabled)
        }
    }

    fun updateGeoDatabase() {
        if (_isUpdatingGeo.value) return
        scope.launch(ioDispatcher) {
            _isUpdatingGeo.value = true
            try {
                env.showToast("正在请求内核更新 Geo 数据库...")
                val ok = env.updateGeoDatabase()
                env.showToast(
                    if (ok) "GeoIP / GeoSite 数据库已更新" else "Geo 更新失败，请确认配置已加载且网络畅通",
                    long = !ok
                )
            } finally {
                _isUpdatingGeo.value = false
            }
        }
    }

    // --- Proxy Selection & Delay Testing ---

    fun selectNode(groupName: String, nodeName: String) {
        val targetGroup = _proxyGroups.value.find { it.name == groupName }
        if (targetGroup != null && !targetGroup.type.equals("select", ignoreCase = true)) {
            scope.launch {
                env.showToast("「$groupName」为 ${targetGroup.type} 自动策略组，请点击右上角测速触发重选")
            }
            return
        }
        env.putSelectorChoice(groupName, nodeName)
        _proxyGroups.update { groups ->
            groups.map { group ->
                if (group.name == groupName) group.copy(now = nodeName) else group
            }
        }
        val epoch = delayCoordinator.currentEpoch()
        scope.launch(ioDispatcher) {
            runCatching { env.patchNativeSelector(groupName, nodeName) }
            env.selectControllerProxy(groupName, nodeName)
            if (epoch == delayCoordinator.currentEpoch()) {
                syncLiveProxyGroupsFromCore()
            }
        }
    }

    fun testGroupDelay(groupName: String) {
        delayCoordinator.testGroupDelay(groupName, ::syncLiveProxyGroupsFromCore)
    }

    fun testNodeDelay(groupName: String, nodeName: String) {
        delayCoordinator.testNodeDelay(groupName, nodeName)
    }

    // --- Profiles CRUD ---

    fun selectProfile(profileId: String) {
        val previousJob = selectProfileJob
        val requestSeq = selectProfileRequestSeq.incrementAndGet()
        val nextJob = scope.launch(ioDispatcher) {
            previousJob?.cancelAndJoin()
            if (requestSeq != selectProfileRequestSeq.get()) return@launch
            val (updatedList, selected) = profileStore.selectProfile(profileId)
            _profiles.value = updatedList
            if (requestSeq != selectProfileRequestSeq.get()) return@launch
            if (selected != null && fileExists(selected.filePath)) {
                applyActiveProfilePath(selected.filePath)
            }
        }
        selectProfileJob = nextJob
        nextJob.invokeOnCompletion {
            if (selectProfileJob === nextJob) {
                selectProfileJob = null
            }
        }
    }

    suspend fun addProfile(name: String, url: String): Result<Unit> = executeProfileImport(
        errorFallback = "导入失败",
        successToast = { "导入成功，已切换至「${it.name}」" },
        action = { profileStore.addProfile(name, url) }
    )

    suspend fun importLocalProfile(name: String, yamlText: String): Result<Unit> = executeProfileImport(
        errorFallback = "本地配置导入失败",
        successToast = { "本地配置「${it.name}」导入成功" },
        action = { profileStore.importLocalYamlProfile(name, yamlText) }
    )

    private suspend fun executeProfileImport(
        errorFallback: String,
        successToast: (Profile) -> String,
        action: suspend () -> Result<Pair<List<Profile>, Profile>>
    ): Result<Unit> {
        if (_isAddingProfile.value) {
            return Result.failure(IllegalStateException("正在导入配置，请稍候"))
        }
        return withContext(ioDispatcher) {
            _isAddingProfile.value = true
            try {
                val result = action()
                val newProfile = result.getOrElse { err ->
                    val msg = err.message ?: errorFallback
                    env.showToast(msg, long = true)
                    return@withContext Result.failure(IllegalStateException(msg, err))
                }
                val (updatedList, createdProfile) = newProfile
                _profiles.value = updatedList
                val applied = applyActiveProfilePath(createdProfile.filePath)
                if (applied) {
                    env.showToast(successToast(createdProfile))
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("配置已保存，但内核加载失败，请检查 YAML 内容"))
                }
            } finally {
                _isAddingProfile.value = false
            }
        }
    }

    fun updateProfile(profileId: String) {
        scope.launch(ioDispatcher) {
            updateProfileInternal(profileId, silent = false)
        }
    }

    fun updateAllRemoteProfiles() {
        scope.launch(ioDispatcher) {
            val remoteTargets = _profiles.value.filter { isRemoteUrl(it.url) }
            if (remoteTargets.isEmpty()) {
                env.showToast("暂无可从远端更新的 HTTP(S) 订阅")
                return@launch
            }
            val outcomes = updateProfilesConcurrently(remoteTargets.map { it.id }, silent = true)
            val successCount = outcomes.count { it }
            val failCount = outcomes.size - successCount
            val msg = if (failCount == 0) {
                "全部 $successCount 个订阅更新成功"
            } else {
                "更新完成：成功 $successCount 个，失败 $failCount 个"
            }
            env.showToast(msg, long = failCount > 0)
        }
    }

    private suspend fun updateProfileInternal(profileId: String, silent: Boolean): Boolean {
        if (profileId in _busyProfileIds.value) return false
        _busyProfileIds.update { it + profileId }
        return try {
            val result = profileStore.updateProfile(profileId)
            var ok = false
            result.onSuccess { (updatedList, updatedProfile) ->
                _profiles.update { current ->
                    if (current.any { it.id == updatedProfile.id }) {
                        current.map { if (it.id == updatedProfile.id) updatedProfile else it }
                    } else {
                        updatedList
                    }
                }
                val applied = if (updatedProfile.isSelected) {
                    applyActiveProfilePath(updatedProfile.filePath, toastOnFailure = !silent)
                } else {
                    true
                }
                ok = applied
                if (applied && !silent) {
                    env.showToast("配置更新成功")
                }
            }.onFailure { err ->
                if (!silent) {
                    env.showToast(err.message ?: "更新失败", long = true)
                }
            }
            ok
        } finally {
            _busyProfileIds.update { it - profileId }
        }
    }

    fun editProfileInfo(profileId: String, name: String, url: String) {
        scope.launch(ioDispatcher) {
            _profiles.value = profileStore.editProfileInfo(profileId, name, url)
        }
    }

    fun deleteProfile(profileId: String) {
        if (profileId in _busyProfileIds.value) {
            scope.launch {
                env.showToast("配置正在更新中，请稍后删除")
            }
            return
        }
        val activeSwitchJob = selectProfileJob
        selectProfileRequestSeq.incrementAndGet()
        selectProfileJob = null
        scope.launch(ioDispatcher) {
            activeSwitchJob?.cancelAndJoin()
            val (updatedList, active) = profileStore.deleteProfile(profileId)
            _profiles.value = updatedList
            if (active != null && fileExists(active.filePath)) {
                applyActiveProfilePath(active.filePath)
            } else {
                delayCoordinator.resetForConfigChange()
                nodeHostPortMap.clear()
                _proxyGroups.value = emptyList()
                if (env.isVpnRunning) {
                    env.stopVpn()
                    env.showToast(
                        if (active == null) {
                            "已删除最后一份配置，VPN 连接已断开"
                        } else {
                            "当前配置不可用，VPN 连接已断开"
                        }
                    )
                }
            }
        }
    }

    suspend fun hasProfileBackup(profileId: String): Boolean = profileStore.hasProfileBackup(profileId)

    suspend fun restoreProfileBackup(profileId: String): Boolean = withContext(ioDispatcher) {
        val result = profileStore.restoreProfileBackup(profileId)
        val restored = result.getOrElse { err ->
            env.showToast(err.message ?: "恢复备份失败", long = true)
            return@withContext false
        }
        val applied = if (restored.isSelected) {
            applyActiveProfilePath(restored.filePath, toastOnFailure = true)
        } else {
            true
        }
        if (applied) {
            env.showToast("已恢复「${restored.name}」到上次备份")
        }
        applied
    }

    private suspend fun applyActiveProfilePath(
        filePath: String,
        toastOnFailure: Boolean = true
    ): Boolean {
        delayCoordinator.resetForConfigChange()
        parseProxyGroupsFromYamlPath(filePath)
        val loaded = env.loadCoreConfig(filePath)
        if (!loaded) {
            if (toastOnFailure) {
                env.showToast("内核加载配置失败，请检查 YAML 语法或代理节点字段", long = true)
            }
            return false
        }
        syncSelectorsToCore()
        syncLiveProxyGroupsFromCore()
        delayCoordinator.warmUpPrimaryGroupDelays()
        return true
    }

    private suspend fun syncLiveProxyGroupsFromCore() {
        val liveProxies = env.queryLiveProxies()
        if (liveProxies != null && liveProxies.isNotEmpty()) {
            _proxyGroups.update { currentGroups ->
                YamlProxyGroupParser.mergeLiveProxies(currentGroups, liveProxies)
            }
            return
        }

        val currentSnapshot = _proxyGroups.value
        val fallbackNames = if (currentSnapshot.isNotEmpty()) {
            currentSnapshot.map { it.name }
        } else {
            env.queryNativeGroupNames()
        }
        if (fallbackNames.isEmpty()) return

        val snapshots = fallbackNames.associateWith { name ->
            runCatching { env.queryNativeGroupSnapshot(name) }.getOrNull()
        }
        _proxyGroups.update { currentGroups ->
            YamlProxyGroupParser.mergeBridgeSnapshots(currentGroups, fallbackNames, snapshots)
        }
    }

    // --- Suspend Dialog Data Loaders & Savers ---

    suspend fun loadProfileYaml(profileId: String): String = profileStore.readProfileYaml(profileId)

    suspend fun saveProfileYaml(profileId: String, content: String): Boolean = withContext(ioDispatcher) {
        val sanitized = ClashConfigEditor.sanitizeYaml(content)
        val syntaxErr = ClashConfigEditor.validateYamlSyntax(sanitized)
        if (syntaxErr != null) {
            env.showToast(syntaxErr, long = true)
            return@withContext false
        }
        val savedProfile = profileStore.writeProfileYaml(profileId, sanitized) ?: return@withContext false
        if (!savedProfile.isSelected) {
            return@withContext true
        }
        val applied = applyActiveProfilePath(savedProfile.filePath, toastOnFailure = true)
        if (applied) {
            return@withContext true
        }
        val rolledBack = profileStore.restoreProfileBackup(profileId)
        if (rolledBack.isSuccess) {
            applyActiveProfilePath(savedProfile.filePath, toastOnFailure = false)
            env.showToast("配置加载失败，已自动恢复到修改前版本", long = true)
        }
        false
    }

    suspend fun loadProfileRulesData(profileId: String): Pair<List<ClashRule>, List<String>> =
        withContext(ioDispatcher) {
            val yaml = profileStore.readProfileYaml(profileId)
            val rules = ClashConfigEditor.parseRules(yaml)
            val policies = linkedSetOf("DIRECT", "REJECT")
            policies.addAll(ClashConfigEditor.extractAvailablePolicies(yaml))
            _proxyGroups.value.forEach { group ->
                policies.add(group.name)
                group.all.forEach { policies.add(it.name) }
            }
            rules to policies.toList()
        }

    suspend fun saveProfileRules(profileId: String, rules: List<ClashRule>): Boolean {
        val yaml = profileStore.readProfileYaml(profileId)
        if (yaml.isBlank()) return false
        val updatedYaml = ClashConfigEditor.saveRules(yaml, rules)
        return saveProfileYaml(profileId, updatedYaml)
    }

    suspend fun loadProfileProxies(profileId: String): List<Map<String, Any>> =
        withContext(ioDispatcher) {
            val yaml = profileStore.readProfileYaml(profileId)
            ClashConfigEditor.extractProxies(yaml)
        }

    suspend fun saveProfileProxies(profileId: String, proxies: List<Map<String, Any>>): Boolean {
        val yaml = profileStore.readProfileYaml(profileId)
        if (yaml.isBlank()) return false
        val updatedYaml = ClashConfigEditor.saveProxies(yaml, proxies)
        return saveProfileYaml(profileId, updatedYaml)
    }

    // --- Connections & Logs ---

    fun closeConnection(connectionId: String) {
        _connections.update { it.filterNot { c -> c.id == connectionId } }
        scope.launch(ioDispatcher) {
            env.closeControllerConnection(connectionId)
        }
    }

    fun closeAllConnections() {
        _connections.value = emptyList()
        scope.launch(ioDispatcher) {
            env.closeAllControllerConnections()
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun syncSelectorsToCore() {
        _proxyGroups.value.forEach { group ->
            if (group.type.equals("select", ignoreCase = true) && group.now.isNotEmpty()) {
                runCatching { env.patchNativeSelector(group.name, group.now) }
            }
        }
    }

    private fun parseProxyGroupsFromYamlPath(filePath: String) {
        try {
            if (!fileExists(filePath)) return
            val parsed = YamlProxyGroupParser.parse(readFileText(filePath)) { groupName ->
                env.getSelectorChoice(groupName)
            }
            delayCoordinator.resetForConfigChange()
            nodeHostPortMap.clear()
            nodeHostPortMap.putAll(parsed.hostPortMap)
            _proxyGroups.value = parsed.groups
        } catch (_: Exception) {
            // Leave existing group snapshot intact if the file cannot be parsed
        }
    }

    private fun isRemoteUrl(url: String): Boolean {
        val clean = url.trim()
        return clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 12_000L
        const val AUTO_UPDATE_INTERVAL_MS = 6L * 60 * 60 * 1000L
        const val MAX_CONCURRENT_PROFILE_UPDATES = 3
        const val MAX_LOG_ENTRIES = 300
    }
}
