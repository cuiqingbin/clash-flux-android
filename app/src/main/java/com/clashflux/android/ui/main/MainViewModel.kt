package com.clashflux.android.ui.main

import android.app.Application
import android.content.Context
import android.net.Network
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clashflux.android.core.ClashVpnService
import com.clashflux.android.core.MihomoControllerClient
import com.clashflux.android.core.MihomoCoreManager
import com.clashflux.android.core.NetworkClients
import com.clashflux.android.core.YamlProxyGroupParser
import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.PerAppMode
import com.clashflux.android.data.ProfileRepository
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.data.ThemeMode
import com.clashflux.android.model.ClashRule
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.ProxyMode
import com.github.kr328.clash.core.bridge.Bridge
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepo = SettingsRepository.getInstance(appContext)
    private val profileRepo = ProfileRepository.getInstance(appContext)

    private val baseProbeHttpClient = NetworkClients.baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedProbeClient: Pair<Network?, OkHttpClient>? = null

    private val selectorPrefs by lazy {
        appContext.getSharedPreferences(ClashVpnService.PREFS_SELECTORS, Context.MODE_PRIVATE)
    }

    private fun resolveProbeClient(underlying: Network?): OkHttpClient {
        if (underlying == null) return baseProbeHttpClient
        val cached = cachedProbeClient
        if (cached != null && cached.first == underlying) {
            return cached.second
        }
        val boundClient = baseProbeHttpClient.newBuilder()
            .socketFactory(underlying.socketFactory)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return underlying.getAllByName(hostname).toList()
                    }
                }
            )
            .build()
        cachedProbeClient = underlying to boundClient
        return boundClient
    }

    private val runtimeEnv = object : MainRuntimeEnvironment {
        override val serviceState: StateFlow<ConnectionStatus> = ClashVpnService.serviceState
        override val appliedRouteSnapshot = ClashVpnService.appliedRouteSnapshot
        override val serviceErrors: StateFlow<String?> = ClashVpnService.lastError
        override val logEvents = MihomoCoreManager.logEvents
        override val isVpnRunning: Boolean get() = ClashVpnService.isRunning.value

        override fun clearServiceError() = ClashVpnService.clearLastError()
        override fun ensureTotalTrafficLoaded() = ClashVpnService.ensureTotalTrafficLoaded(appContext)
        override fun startVpn() = ClashVpnService.start(appContext)
        override fun stopVpn() = ClashVpnService.stop(appContext)
        override fun restartVpn() = ClashVpnService.restart(appContext)

        override suspend fun initCore() = MihomoCoreManager.initCore(appContext)
        override suspend fun loadCoreConfig(path: String) = MihomoCoreManager.loadConfig(appContext, path)
        override suspend fun applySettingsUpdate(settings: AppSettings) =
            MihomoCoreManager.applySettingsUpdate(appContext, settings)

        override suspend fun updateGeoDatabase() = MihomoCoreManager.updateGeoDatabase()

        override fun getSelectorChoice(groupName: String): String? = selectorPrefs.getString(groupName, null)
        override fun putSelectorChoice(groupName: String, nodeName: String) {
            selectorPrefs.edit { putString(groupName, nodeName) }
        }

        override fun patchNativeSelector(groupName: String, nodeName: String) {
            Bridge.nativePatchSelector(groupName, nodeName)
        }

        override suspend fun queryLiveProxies() = MihomoControllerClient.getProxies()

        override suspend fun queryNativeGroupNames(): List<String> =
            YamlProxyGroupParser.parseGroupNamesJson(
                runCatching { Bridge.nativeQueryGroupNames(false) }.getOrDefault("")
            )

        override suspend fun queryNativeGroupSnapshot(groupName: String) =
            YamlProxyGroupParser.parseBridgeGroupSnapshot(Bridge.nativeQueryGroup(groupName, "default"))

        override suspend fun selectControllerProxy(groupName: String, nodeName: String) =
            MihomoControllerClient.selectProxy(groupName, nodeName)

        override suspend fun testControllerGroupDelay(groupName: String) =
            MihomoControllerClient.testGroupDelay(groupName)

        override suspend fun testControllerProxyDelay(proxyName: String) =
            MihomoControllerClient.testProxyDelay(proxyName)

        override suspend fun nativeHealthCheckGroup(groupName: String): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            try {
                val deferred = CompletableDeferred<Unit>()
                Bridge.nativeHealthCheck(deferred, groupName)
                withTimeoutOrNull(4500) { deferred.await() }
                val snapshot = YamlProxyGroupParser.parseBridgeGroupSnapshot(
                    Bridge.nativeQueryGroup(groupName, "default")
                )
                if (snapshot != null) {
                    result.putAll(snapshot.delays)
                }
            } catch (_: Throwable) {}
            return result
        }

        override suspend fun measureDirectHttpDelay(): Int {
            return try {
                val start = System.currentTimeMillis()
                val req = Request.Builder()
                    .url(MihomoControllerClient.DEFAULT_TEST_URL)
                    .head()
                    .build()
                val client = resolveProbeClient(ClashVpnService.currentUnderlyingNetwork)
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 204) {
                        (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
                    } else {
                        -1
                    }
                }
            } catch (_: Exception) {
                -1
            }
        }

        override suspend fun measureTcpConnectDelay(host: String, port: Int): Int {
            val underlying = ClashVpnService.currentUnderlyingNetwork
            return NetworkClients.measureTcpConnect(
                host = host,
                port = port,
                resolver = { target ->
                    underlying?.getAllByName(target)?.firstOrNull()
                        ?: if (underlying == null) {
                            InetAddress.getByName(target)
                        } else {
                            throw UnknownHostException("Unable to resolve $target on underlying network")
                        }
                },
                socketFactory = { underlying?.socketFactory?.createSocket() ?: Socket() }
            )
        }

        override suspend fun getLiveConnections() = MihomoControllerClient.getConnections()
        override suspend fun closeControllerConnection(id: String) = MihomoControllerClient.closeConnection(id)
        override suspend fun closeAllControllerConnections() = MihomoControllerClient.closeAllConnections()

        override suspend fun showToast(message: String, long: Boolean) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    message,
                    if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val coordinator = MainCoordinator(
        scope = viewModelScope,
        ioDispatcher = Dispatchers.IO,
        settingsStore = settingsRepo,
        profileStore = profileRepo,
        env = runtimeEnv
    )

    val status: StateFlow<ConnectionStatus> = coordinator.status
    val currentSpeed = ClashVpnService.currentSpeed
    val sessionTraffic = ClashVpnService.sessionTraffic
    val totalTraffic = ClashVpnService.totalTraffic
    val connectedDurationSeconds = ClashVpnService.connectedDurationSeconds
    val speedHistory = ClashVpnService.speedHistory

    val settingsState = coordinator.settingsState
    val selectedMode = coordinator.selectedMode
    val profiles = coordinator.profiles
    val proxyGroups = coordinator.proxyGroups
    val connections = coordinator.connections
    val logs = coordinator.logs
    val isAddingProfile = coordinator.isAddingProfile
    val busyProfileIds = coordinator.busyProfileIds
    val isUpdatingGeo = coordinator.isUpdatingGeo
    val appliedRouteSnapshot = ClashVpnService.appliedRouteSnapshot
    val hasPendingVpnRestart = coordinator.hasPendingVpnRestart

    init {
        coordinator.start(autoLoadSavedProfiles = true)
    }

    fun setConnectionsMonitoringActive(active: Boolean) = coordinator.setConnectionsMonitoringActive(active)
    fun startVpnService() = coordinator.startVpnService()
    fun stopVpnService() = coordinator.stopVpnService()
    fun restartVpnIfRunning() = coordinator.restartVpnIfRunning()
    fun cancelConnecting() = coordinator.cancelConnecting()
    fun toggleConnection() = coordinator.toggleConnection()

    fun setProxyMode(mode: ProxyMode) = coordinator.setProxyMode(mode)
    fun setAllowLan(enabled: Boolean) = coordinator.setAllowLan(enabled)
    fun setIpv6Enabled(enabled: Boolean) = coordinator.setIpv6Enabled(enabled)
    fun setLogLevel(level: String) = coordinator.setLogLevel(level)
    fun setStartOnBoot(enabled: Boolean) = coordinator.setStartOnBoot(enabled)
    fun setBypassLan(enabled: Boolean) = coordinator.setBypassLan(enabled)
    fun setAutoUpdateProfiles(enabled: Boolean) = coordinator.setAutoUpdateProfiles(enabled)
    suspend fun setProxyPorts(mixedPort: Int, httpPort: Int, socksPort: Int): Result<Unit> =
        coordinator.setProxyPorts(mixedPort, httpPort, socksPort)

    fun updateGeoDatabase() = coordinator.updateGeoDatabase()

    fun setPerAppMode(mode: PerAppMode) = coordinator.setPerAppMode(mode)
    fun togglePerAppPackage(packageName: String) = coordinator.togglePerAppPackage(packageName)
    fun setPerAppPackages(packages: Set<String>) = coordinator.setPerAppPackages(packages)
    fun setHideSystemApps(hide: Boolean) = coordinator.setHideSystemApps(hide)
    fun setThemeMode(mode: ThemeMode) = coordinator.setThemeMode(mode)
    fun setDynamicColor(enabled: Boolean) = coordinator.setDynamicColor(enabled)

    fun selectNode(groupName: String, nodeName: String) = coordinator.selectNode(groupName, nodeName)
    fun testGroupDelay(groupName: String) = coordinator.testGroupDelay(groupName)
    fun testNodeDelay(groupName: String, nodeName: String) = coordinator.testNodeDelay(groupName, nodeName)

    fun selectProfile(profileId: String) = coordinator.selectProfile(profileId)
    suspend fun addProfile(name: String, url: String): Result<Unit> = coordinator.addProfile(name, url)
    suspend fun importLocalProfile(name: String, yamlText: String): Result<Unit> =
        coordinator.importLocalProfile(name, yamlText)

    fun updateProfile(profileId: String) = coordinator.updateProfile(profileId)
    fun updateAllRemoteProfiles() = coordinator.updateAllRemoteProfiles()
    fun editProfileInfo(profileId: String, name: String, url: String) =
        coordinator.editProfileInfo(profileId, name, url)

    fun deleteProfile(profileId: String) = coordinator.deleteProfile(profileId)
    suspend fun hasProfileBackup(profileId: String): Boolean = coordinator.hasProfileBackup(profileId)
    suspend fun restoreProfileBackup(profileId: String): Boolean = coordinator.restoreProfileBackup(profileId)

    suspend fun loadProfileYaml(profileId: String) = coordinator.loadProfileYaml(profileId)
    suspend fun saveProfileYaml(profileId: String, content: String) = coordinator.saveProfileYaml(profileId, content)
    suspend fun loadProfileRulesData(profileId: String) = coordinator.loadProfileRulesData(profileId)
    suspend fun saveProfileRules(profileId: String, rules: List<ClashRule>) =
        coordinator.saveProfileRules(profileId, rules)

    suspend fun loadProfileProxies(profileId: String) = coordinator.loadProfileProxies(profileId)
    suspend fun saveProfileProxies(profileId: String, proxies: List<Map<String, Any>>) =
        coordinator.saveProfileProxies(profileId, proxies)

    fun closeConnection(connectionId: String) = coordinator.closeConnection(connectionId)
    fun closeAllConnections() = coordinator.closeAllConnections()
    fun clearLogs() = coordinator.clearLogs()
}
