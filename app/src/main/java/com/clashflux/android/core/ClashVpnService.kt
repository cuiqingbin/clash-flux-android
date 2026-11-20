package com.clashflux.android.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.clashflux.android.MainActivity
import com.clashflux.android.R
import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.PerAppMode
import com.clashflux.android.data.ProfileRepository
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.data.VpnRouteSnapshot
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.TrafficStats
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ClashVpnService : VpnService() {

    internal enum class TransitionKind {
        START,
        STOP
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleMutex = Mutex()
    @Volatile
    private var transitionJob: Job? = null
    @Volatile
    private var transitionToken: Long = 0L
    @Volatile
    private var activeTransitionKind: TransitionKind? = null
    @Volatile
    private var latestStartId: Int = 0
    @Volatile
    private var trafficPersistDirty: Boolean = false
    private var tickerJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "ClashVpnService"
        const val ACTION_START = "com.clashflux.android.START_VPN"
        const val ACTION_STOP = "com.clashflux.android.STOP_VPN"
        const val ACTION_RESTART = "com.clashflux.android.RESTART_VPN"
        const val CHANNEL_ID = "clash_flux_vpn_channel"
        const val NOTIFICATION_ID = 1001
        private const val PREFS_TRAFFIC = "clash_flux_traffic"
        const val PREFS_SELECTORS = "clash_flux_selectors"
        internal const val TRAFFIC_PERSIST_INTERVAL_MS = 20_000L

        internal fun shouldIgnoreDuplicateStart(
            restartExisting: Boolean,
            state: ConnectionStatus,
            activeTransitionKind: TransitionKind?,
            hasActiveTransitionJob: Boolean
        ): Boolean {
            if (restartExisting) return false
            if (activeTransitionKind == TransitionKind.STOP || state == ConnectionStatus.Disconnecting) {
                return false
            }
            return state == ConnectionStatus.Connected ||
                (hasActiveTransitionJob && activeTransitionKind == TransitionKind.START)
        }

        internal fun shouldDispatchStopIntent(
            state: ConnectionStatus,
            hasActiveInstance: Boolean
        ): Boolean = hasActiveInstance || state != ConnectionStatus.Disconnected

        internal fun hasPendingRouteRestart(
            state: ConnectionStatus,
            snapshot: VpnRouteSnapshot?,
            settings: AppSettings
        ): Boolean = state == ConnectionStatus.Connected && snapshot != null && snapshot.differsFrom(settings)

        internal fun shouldPersistTraffic(
            dirty: Boolean,
            elapsedSinceLastPersistMs: Long,
            forceFlush: Boolean,
            intervalMs: Long = TRAFFIC_PERSIST_INTERVAL_MS
        ): Boolean {
            return dirty && (forceFlush || elapsedSinceLastPersistMs >= intervalMs)
        }

        @Volatile
        internal var hasActiveServiceInstance: Boolean = false
            private set

        private val _appliedRouteSnapshot = MutableStateFlow<VpnRouteSnapshot?>(null)
        internal val appliedRouteSnapshot: StateFlow<VpnRouteSnapshot?> = _appliedRouteSnapshot.asStateFlow()

        private val _serviceState = MutableStateFlow(ConnectionStatus.Disconnected)
        val serviceState: StateFlow<ConnectionStatus> = _serviceState.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private fun updateServiceState(state: ConnectionStatus) {
            _serviceState.value = state
            _isRunning.value = state == ConnectionStatus.Connected
        }

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()

        private val _connectedDurationSeconds = MutableStateFlow(0L)
        val connectedDurationSeconds = _connectedDurationSeconds.asStateFlow()

        private val _currentSpeed = MutableStateFlow(TrafficStats(0, 0))
        val currentSpeed = _currentSpeed.asStateFlow()

        private val _sessionTraffic = MutableStateFlow(TrafficStats(0, 0))
        val sessionTraffic = _sessionTraffic.asStateFlow()

        private val _totalTraffic = MutableStateFlow(TrafficStats(0, 0))
        val totalTraffic = _totalTraffic.asStateFlow()

        private val _speedHistory = MutableStateFlow<List<TrafficStats>>(List(25) { TrafficStats(0, 0) })
        val speedHistory = _speedHistory.asStateFlow()

        @Volatile
        private var totalTrafficLoaded = false

        @Volatile
        private var connectedAtRealtime: Long = 0L

        @Volatile
        private var sessionBaselineUp: Long = 0L

        @Volatile
        private var sessionBaselineDown: Long = 0L

        @Volatile
        var currentUnderlyingNetwork: Network? = null
            private set

        fun ensureTotalTrafficLoaded(context: Context) {
            if (totalTrafficLoaded) return
            synchronized(this) {
                if (totalTrafficLoaded) return
                val prefs = context.applicationContext.getSharedPreferences(PREFS_TRAFFIC, Context.MODE_PRIVATE)
                val savedUp = prefs.getLong("total_up", 0L)
                val savedDown = prefs.getLong("total_down", 0L)
                _totalTraffic.value = TrafficStats(up = savedUp, down = savedDown)
                totalTrafficLoaded = true
            }
        }

        fun clearLastError() {
            _lastError.value = null
        }

        fun start(context: Context) {
            launchForegroundAction(context, ACTION_START)
        }

        fun stop(context: Context) {
            if (!shouldDispatchStopIntent(_serviceState.value, hasActiveServiceInstance)) {
                return
            }
            val intent = Intent(context, ClashVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop service skipped from restricted background state: ${e.message}")
                if (_serviceState.value != ConnectionStatus.Disconnected) {
                    updateServiceState(ConnectionStatus.Disconnected)
                    ClashTileService.requestTileUpdate(context)
                }
            }
        }

        fun restart(context: Context) {
            launchForegroundAction(context, ACTION_RESTART)
        }

        private fun launchForegroundAction(context: Context, actionName: String) {
            ensureTotalTrafficLoaded(context)
            clearLastError()
            val intent = Intent(context, ClashVpnService::class.java).apply {
                action = actionName
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground VPN service ($actionName)", e)
                _lastError.value = "系统限制后台启动前台服务，请打开应用后开启 VPN"
                if (_serviceState.value != ConnectionStatus.Connected) {
                    updateServiceState(ConnectionStatus.Disconnected)
                }
                ClashTileService.requestTileUpdate(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        hasActiveServiceInstance = true
        ensureTotalTrafficLoaded(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START, SERVICE_INTERFACE -> enqueueStartOrRestart(restartExisting = false)
            ACTION_RESTART -> enqueueStartOrRestart(restartExisting = true)
            ACTION_STOP -> enqueueStop()
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(title: String, content: String) {
        val notification = buildNotification(title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun enqueueStartOrRestart(restartExisting: Boolean) {
        val currentState = _serviceState.value
        if (shouldIgnoreDuplicateStart(restartExisting, currentState, activeTransitionKind, transitionJob?.isActive == true)) {
            if (currentState == ConnectionStatus.Connected) {
                promoteToForeground("正在运行", "已连接代理服务器")
            } else {
                promoteToForeground("正在连接", "正在初始化 Mihomo 内核...")
            }
            return
        }

        val token = ++transitionToken
        activeTransitionKind = TransitionKind.START
        updateServiceState(ConnectionStatus.Connecting)
        val title = if (restartExisting) "正在重启" else "正在连接"
        val body = if (restartExisting) "正在重建 TUN 隧道并应用最新设置..." else "正在初始化 Mihomo 内核..."
        promoteToForeground(title, body)
        ClashTileService.requestTileUpdate(this)
        ensureTotalTrafficLoaded(applicationContext)

        val previousJob = transitionJob
        val nextJob = serviceScope.launch {
            previousJob?.cancelAndJoin()
            updateServiceState(ConnectionStatus.Connecting)
            lifecycleMutex.withLock {
                if (restartExisting || _isRunning.value || vpnInterface != null) {
                    teardownVpnLocked(stopServiceAfter = false, targetState = ConnectionStatus.Connecting)
                }
                startVpnLocked()
            }
        }
        transitionJob = nextJob
        nextJob.invokeOnCompletion {
            if (transitionToken == token && activeTransitionKind == TransitionKind.START) {
                activeTransitionKind = null
            }
        }
    }

    private fun enqueueStop() {
        val stopStartId = latestStartId
        val token = ++transitionToken
        activeTransitionKind = TransitionKind.STOP
        if (_serviceState.value != ConnectionStatus.Disconnected || transitionJob?.isActive == true) {
            updateServiceState(ConnectionStatus.Disconnecting)
            ClashTileService.requestTileUpdate(this)
        }
        val previousJob = transitionJob
        previousJob?.cancel()
        val nextJob = serviceScope.launch {
            runCatching { previousJob?.join() }
            lifecycleMutex.withLock {
                teardownVpnLocked(
                    stopServiceAfter = true,
                    targetState = ConnectionStatus.Disconnected,
                    stopStartId = stopStartId
                )
            }
        }
        transitionJob = nextJob
        nextJob.invokeOnCompletion {
            if (transitionToken == token && activeTransitionKind == TransitionKind.STOP) {
                activeTransitionKind = null
            }
        }
    }

    private suspend fun startVpnLocked() {
        try {
            val appContext = applicationContext
            MihomoCoreManager.initCore(appContext)

            val configToLoad = resolveActiveProfileConfig(appContext)
            if (configToLoad == null) {
                _lastError.value = "未找到可用配置，请先导入订阅"
                teardownVpnLocked(stopServiceAfter = true, targetState = ConnectionStatus.Disconnected)
                return
            }

            val loaded = MihomoCoreManager.loadConfig(appContext, configToLoad.absolutePath)
            if (!loaded) {
                _lastError.value = "配置加载失败，请检查节点或 YAML 格式"
                teardownVpnLocked(stopServiceAfter = true, targetState = ConnectionStatus.Disconnected)
                return
            }

            applySavedSelectorsToCore(appContext)
            val settings = SettingsRepository.getInstance(appContext).getSnapshot()

            val pfd = buildVpnInterface(settings).establish()
            if (pfd == null) {
                _lastError.value = "VPN 权限未授予或系统拒绝建立隧道"
                Log.e(TAG, "VpnBuilder.establish() returned null")
                teardownVpnLocked(stopServiceAfter = true, targetState = ConnectionStatus.Disconnected)
                return
            }
            vpnInterface = pfd

            val (tunFd, ownsDupFd) = try {
                pfd.dup().detachFd() to true
            } catch (_: Exception) {
                pfd.fd to false
            }

            val tunStarted = MihomoCoreManager.startTun(this@ClashVpnService, tunFd, settings.ipv6Enabled)
            if (!tunStarted) {
                if (ownsDupFd) {
                    runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
                }
                _lastError.value = "启动内核 TUN 隧道失败"
                teardownVpnLocked(stopServiceAfter = true, targetState = ConnectionStatus.Disconnected)
                return
            }

            val (baseUp, baseDown) = MihomoCoreManager.queryTotalTraffic()
            sessionBaselineUp = baseUp
            sessionBaselineDown = baseDown
            connectedAtRealtime = SystemClock.elapsedRealtime()
            _sessionTraffic.value = TrafficStats(0, 0)
            _connectedDurationSeconds.value = 0L
            _currentSpeed.value = TrafficStats(0, 0)

            registerUnderlyingNetworkCallback()
            _appliedRouteSnapshot.value = VpnRouteSnapshot(
                perAppMode = settings.perAppMode,
                perAppPackages = settings.perAppPackages,
                ipv6Enabled = settings.ipv6Enabled,
                bypassLan = settings.bypassLan
            )
            updateServiceState(ConnectionStatus.Connected)
            updateForegroundNotification("正在运行", "已连接代理服务器")
            ClashTileService.requestTileUpdate(this@ClashVpnService)
            startTrafficAndDurationTicker()
        } catch (e: CancellationException) {
            teardownVpnLocked(stopServiceAfter = false, targetState = _serviceState.value)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed", e)
            _lastError.value = "启动失败: ${e.message ?: "未知错误"}"
            teardownVpnLocked(stopServiceAfter = true, targetState = ConnectionStatus.Disconnected)
        }
    }

    private fun buildVpnInterface(settings: AppSettings): Builder {
        val builder = Builder()
            .setSession("ClashFlux")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addDnsServer("172.19.0.2")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (settings.bypassLan) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.addRoute("0.0.0.0", 0)
                VpnRouteCalculator.ipv4ExcludeRoutesForBypass().forEach { route ->
                    runCatching {
                        builder.excludeRoute(IpPrefix(route.address, route.prefixLength))
                    }
                }
            } else {
                // Android 8-12 lacks excludeRoute(): add complement public CIDRs + TUN DNS subnet
                VpnRouteCalculator.ipv4IncludeRoutesForLegacyBypass().forEach { route ->
                    builder.addRoute(route.address, route.prefixLength)
                }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (settings.ipv6Enabled) {
            try {
                builder.addAddress("fdfe:dcba:9876::1", 126)
                builder.addDnsServer("fdfe:dcba:9876::2")
                if (settings.bypassLan) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        builder.addRoute("::", 0)
                        VpnRouteCalculator.ipv6ExcludeRoutesForBypass().forEach { route ->
                            runCatching {
                                builder.excludeRoute(IpPrefix(route.address, route.prefixLength))
                            }
                        }
                    } else {
                        VpnRouteCalculator.ipv6IncludeRoutesForLegacyBypass().forEach { route ->
                            builder.addRoute(route.address, route.prefixLength)
                        }
                    }
                } else {
                    builder.addRoute("::", 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add IPv6 to VPN: ${e.message}")
            }
        }

        applyPerAppProxyRules(builder, settings)
        return builder
    }

    private fun applyPerAppProxyRules(builder: Builder, settings: AppSettings) {
        when (settings.perAppMode) {
            PerAppMode.ALL -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
            PerAppMode.BYPASS_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                settings.perAppPackages
                    .filter { it != packageName }
                    .forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            }
            PerAppMode.PROXY_SELECTED -> {
                val allowed = settings.perAppPackages.filter { it != packageName }
                if (allowed.isEmpty()) {
                    // Empty whitelist would otherwise block self-exclusion; fall back safely
                    runCatching { builder.addDisallowedApplication(packageName) }
                } else {
                    var addedAny = false
                    allowed.forEach { pkg ->
                        if (runCatching { builder.addAllowedApplication(pkg) }.isSuccess) {
                            addedAny = true
                        }
                    }
                    if (!addedAny) {
                        runCatching { builder.addDisallowedApplication(packageName) }
                    }
                }
            }
        }
    }

    private fun registerUnderlyingNetworkCallback() {
        unregisterUnderlyingNetworkCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                syncUnderlyingNetworkAndDns(cm, network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateUnderlyingDns(linkProperties)
            }

            override fun onLost(network: Network) {
                if (currentUnderlyingNetwork == network) {
                    currentUnderlyingNetwork = null
                }
                runCatching { setUnderlyingNetworks(null) }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        runCatching {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            cm.activeNetwork?.let { active ->
                val caps = cm.getNetworkCapabilities(active)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                    syncUnderlyingNetworkAndDns(cm, active)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun syncUnderlyingNetworkAndDns(cm: ConnectivityManager, network: Network) {
        currentUnderlyingNetwork = network
        runCatching { setUnderlyingNetworks(arrayOf(network)) }
        cm.getLinkProperties(network)?.let { updateUnderlyingDns(it) }
    }

    private fun updateUnderlyingDns(linkProperties: LinkProperties) {
        val dnsList = linkProperties.dnsServers
            .mapNotNull { it.hostAddress?.takeIf { addr -> !addr.startsWith("172.19.") && !addr.startsWith("fdfe:dcba:") } }
            .distinct()
        if (dnsList.isNotEmpty()) {
            MihomoCoreManager.notifyDnsChanged(dnsList)
        }
    }

    private fun unregisterUnderlyingNetworkCallback() {
        currentUnderlyingNetwork = null
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private suspend fun resolveActiveProfileConfig(context: Context): File? {
        val profiles = ProfileRepository.getInstance(context).loadProfiles()
        val selected = profiles.find { it.isSelected } ?: profiles.firstOrNull()
        if (selected != null) {
            val f = File(selected.filePath)
            if (f.exists() && f.length() > 0L) return f
        }

        val activeConfig = File(File(context.filesDir, "clash"), "config.yaml")
        if (activeConfig.exists() && activeConfig.length() > 0L) {
            return activeConfig
        }

        val profilesDir = File(context.filesDir, "profiles")
        return profilesDir.listFiles()?.firstOrNull { it.extension == "yaml" && it.length() > 0L }
    }

    private fun applySavedSelectorsToCore(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_SELECTORS, Context.MODE_PRIVATE)
            for ((groupName, nodeNameObj) in prefs.all) {
                val nodeName = nodeNameObj as? String
                if (!groupName.isNullOrBlank() && !nodeName.isNullOrBlank()) {
                    runCatching {
                        com.github.kr328.clash.core.bridge.Bridge.nativePatchSelector(groupName, nodeName)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun persistTotalTraffic(forceFlush: Boolean, lastPersistElapsedMs: Long): Long {
        val now = SystemClock.elapsedRealtime()
        if (!shouldPersistTraffic(trafficPersistDirty, now - lastPersistElapsedMs, forceFlush)) {
            return lastPersistElapsedMs
        }
        val snapshot = _totalTraffic.value
        val prefs = applicationContext.getSharedPreferences(PREFS_TRAFFIC, Context.MODE_PRIVATE)
        prefs.edit {
            putLong("total_up", snapshot.up)
            putLong("total_down", snapshot.down)
        }
        trafficPersistDirty = false
        return now
    }

    private fun startTrafficAndDurationTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            var lastPersistElapsed = SystemClock.elapsedRealtime()
            while (isActive && _serviceState.value == ConnectionStatus.Connected) {
                delay(1000)
                if (_serviceState.value != ConnectionStatus.Connected) break

                val elapsedSec = ((SystemClock.elapsedRealtime() - connectedAtRealtime) / 1000L).coerceAtLeast(0L)
                _connectedDurationSeconds.value = elapsedSec

                val (realUp, realDown) = MihomoCoreManager.queryTraffic()
                val (coreTotalUp, coreTotalDown) = MihomoCoreManager.queryTotalTraffic()
                val stats = TrafficStats(up = realUp, down = realDown)
                _currentSpeed.value = stats

                if (coreTotalUp < sessionBaselineUp) sessionBaselineUp = 0L
                if (coreTotalDown < sessionBaselineDown) sessionBaselineDown = 0L

                val prevSession = _sessionTraffic.value
                val coreSessionUp = (coreTotalUp - sessionBaselineUp).coerceAtLeast(0L)
                val coreSessionDown = (coreTotalDown - sessionBaselineDown).coerceAtLeast(0L)
                val newSessionUp = maxOf(prevSession.up + realUp, coreSessionUp)
                val newSessionDown = maxOf(prevSession.down + realDown, coreSessionDown)
                val deltaUp = (newSessionUp - prevSession.up).coerceAtLeast(0L)
                val deltaDown = (newSessionDown - prevSession.down).coerceAtLeast(0L)

                _sessionTraffic.value = TrafficStats(up = newSessionUp, down = newSessionDown)

                if (deltaUp > 0L || deltaDown > 0L) {
                    _totalTraffic.update { oldTotal ->
                        TrafficStats(
                            up = maxOf(oldTotal.up + deltaUp, newSessionUp),
                            down = maxOf(oldTotal.down + deltaDown, newSessionDown)
                        )
                    }
                    trafficPersistDirty = true
                    lastPersistElapsed = persistTotalTraffic(
                        forceFlush = false,
                        lastPersistElapsedMs = lastPersistElapsed
                    )
                }

                _speedHistory.update { old ->
                    (old.drop(1) + stats).takeLast(25)
                }
            }
        }
    }

    private fun teardownVpnLocked(
        stopServiceAfter: Boolean,
        targetState: ConnectionStatus = ConnectionStatus.Disconnected,
        stopStartId: Int? = null
    ) {
        tickerJob?.cancel()
        tickerJob = null
        persistTotalTraffic(forceFlush = true, lastPersistElapsedMs = 0L)
        unregisterUnderlyingNetworkCallback()

        val wasActive = _isRunning.value || vpnInterface != null
        if (targetState == ConnectionStatus.Disconnected) {
            _appliedRouteSnapshot.value = null
        }
        updateServiceState(targetState)
        val zero = TrafficStats(0, 0)
        _currentSpeed.value = zero
        _connectedDurationSeconds.value = 0L
        ClashTileService.requestTileUpdate(this)

        if (wasActive) {
            MihomoCoreManager.stopTun()
            runCatching { vpnInterface?.close() }
            vpnInterface = null
        }

        if (stopServiceAfter && activeTransitionKind != TransitionKind.START) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            val effectiveStartId = stopStartId ?: latestStartId
            if (effectiveStartId > 0) {
                stopSelfResult(effectiveStartId)
            } else {
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ClashFlux VPN 服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示当前代理连接状态与流量"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateForegroundNotification(title: String, content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(title, content)) }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ClashVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClashFlux: $title")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开连接", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        enqueueStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        hasActiveServiceInstance = false
        val pendingJob = transitionJob
        pendingJob?.cancel()
        tickerJob?.cancel()
        tickerJob = null
        persistTotalTraffic(forceFlush = true, lastPersistElapsedMs = 0L)
        unregisterUnderlyingNetworkCallback()

        val lingeringInterface = vpnInterface
        vpnInterface = null
        val wasRunning = _isRunning.value
        _appliedRouteSnapshot.value = null
        updateServiceState(ConnectionStatus.Disconnected)
        _currentSpeed.value = TrafficStats(0, 0)
        _connectedDurationSeconds.value = 0L
        ClashTileService.requestTileUpdate(this)

        val needsNativeCleanup = wasRunning || lingeringInterface != null || pendingJob?.isActive == true
        if (needsNativeCleanup) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { pendingJob?.join() }
                lifecycleMutex.withLock {
                    MihomoCoreManager.stopTun()
                    runCatching { lingeringInterface?.close() }
                }
                serviceScope.cancel()
            }
        } else {
            serviceScope.cancel()
        }
        super.onDestroy()
    }
}
