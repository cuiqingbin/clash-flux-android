package com.clashflux.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.util.Log
import com.clashflux.android.BuildConfig
import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.ProfileRepository
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.model.LogEntry
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.LogcatInterface
import com.github.kr328.clash.core.bridge.TunInterface
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object MihomoCoreManager {

    private const val TAG = "MihomoCoreManager"
    private const val IPV4_GATEWAY = "172.19.0.1/30"
    private const val IPV4_PORTAL = "172.19.0.2"
    private const val IPV6_GATEWAY = "fdfe:dcba:9876::1/126"
    private const val IPV6_PORTAL = "fdfe:dcba:9876::2"

    private val coreMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val logSubscribed = AtomicBoolean(false)

    @Volatile
    private var isInitialized = false

    @Volatile
    private var tunActive = false

    private val _logEvents = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logEvents = _logEvents.asSharedFlow()

    suspend fun initCore(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        coreMutex.withLock {
            if (isInitialized) return@withLock

            val appContext = context.applicationContext
            val homeDir = File(appContext.filesDir, "clash").apply { mkdirs() }

            val versionFile = File(homeDir, "geo_assets.version")
            val currentVersion = BuildConfig.VERSION_CODE.toString()
            val forceRefreshGeo = runCatching { versionFile.readText().trim() != currentVersion }.getOrDefault(true)

            val geoipOk = copyAssetIfNeeded(
                appContext,
                "geoip.metadb",
                File(homeDir, "geoip.metadb"),
                overwrite = forceRefreshGeo
            )
            val geositeOk = copyAssetIfNeeded(
                appContext,
                "geosite.dat",
                File(homeDir, "geosite.dat"),
                overwrite = forceRefreshGeo
            )
            if (forceRefreshGeo && geoipOk && geositeOk) {
                runCatching { ProfileRepository.writeTextAtomic(versionFile, currentVersion) }
            }

            val defaultConfigFile = File(homeDir, "config.yaml")
            if (!defaultConfigFile.exists()) {
                ProfileRepository.writeTextAtomic(defaultConfigFile, getDefaultConfig())
            }

            try {
                Bridge.nativeInit(
                    home = homeDir.absolutePath,
                    geoDir = homeDir.absolutePath,
                    uid = Process.myUid()
                )
                subscribeLogcatOnce()
                isInitialized = true
            } catch (e: Throwable) {
                Log.e(TAG, "Bridge.nativeInit failed", e)
            }
        }
    }

    private fun subscribeLogcatOnce() {
        if (!logSubscribed.compareAndSet(false, true)) return
        try {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(message: String) {
                    parseLogMessage(message)?.let { entry ->
                        _logEvents.tryEmit(entry)
                    }
                }
            })
        } catch (e: Throwable) {
            logSubscribed.set(false)
            Log.w(TAG, "Bridge.nativeSubscribeLogcat failed: ${e.message}")
        }
    }

    suspend fun loadConfig(context: Context, configPath: String): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val configFile = File(configPath)
        if (!configFile.exists() || configFile.length() == 0L) {
            return@withContext false
        }

        initCore(appContext)
        if (!isInitialized) return@withContext false

        val settings = SettingsRepository.getInstance(appContext).getSnapshot()
        val homeDir = File(appContext.filesDir, "clash")
        val targetFile = File(homeDir, "config.yaml")

        coreMutex.withLock {
            try {
                val rawText = configFile.readText()
                val runtimeYaml = ProfileRepository.prepareRuntimeYaml(
                    rawProfileYaml = rawText,
                    bypassLan = settings.bypassLan,
                    mixedPort = settings.mixedPort,
                    httpPort = settings.httpPort,
                    socksPort = settings.socksPort
                )
                if (runtimeYaml.isBlank()) return@withContext false
                ProfileRepository.writeTextAtomic(targetFile, runtimeYaml)
            } catch (e: Exception) {
                Log.e(TAG, "Failed preparing runtime config.yaml", e)
                return@withContext false
            }

            return@withContext try {
                applySessionOverrideLocked(settings)
                val deferred = CompletableDeferred<Unit>()
                Bridge.nativeLoad(deferred, homeDir.absolutePath)
                val completed = withTimeoutOrNull(5000) {
                    deferred.await()
                    true
                }
                if (completed == true) {
                    Log.i(TAG, "Bridge.nativeLoad completed for ${homeDir.absolutePath}")
                    true
                } else {
                    Log.e(TAG, "Bridge.nativeLoad timed out")
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Bridge.nativeLoad error", e)
                false
            }
        }
    }

    suspend fun applySettingsUpdate(context: Context, settings: AppSettings) = withContext(Dispatchers.IO) {
        initCore(context.applicationContext)
        if (!isInitialized) return@withContext
        coreMutex.withLock {
            applySessionOverrideLocked(settings)
        }
        MihomoControllerClient.patchConfig(
            mode = settings.proxyMode,
            allowLan = settings.allowLan,
            ipv6 = settings.ipv6Enabled,
            logLevel = settings.logLevel,
            mixedPort = settings.mixedPort,
            port = settings.httpPort,
            socksPort = settings.socksPort
        )
    }

    suspend fun updateGeoDatabase(): Boolean = MihomoControllerClient.updateGeoDatabase()

    private fun applySessionOverrideLocked(settings: AppSettings) {
        try {
            val overrideJson = MihomoControllerClient.buildOverrideJson(settings)
            Bridge.nativeWriteOverride(1, overrideJson)
        } catch (e: Throwable) {
            Log.w(TAG, "Bridge.nativeWriteOverride failed: ${e.message}")
        }
    }

    fun startTun(vpnService: VpnService, fd: Int, ipv6Enabled: Boolean): Boolean {
        val connectivityManager = vpnService.getSystemService(ConnectivityManager::class.java)
        val tunInterface = object : TunInterface {
            override fun markSocket(fd: Int) {
                vpnService.protect(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                return resolveSocketUid(connectivityManager, protocol, source, target)
            }
        }

        val gateway = if (ipv6Enabled) "$IPV4_GATEWAY,$IPV6_GATEWAY" else IPV4_GATEWAY
        val portal = if (ipv6Enabled) "$IPV4_PORTAL,$IPV6_PORTAL" else IPV4_PORTAL
        val dns = portal

        return try {
            Log.d(TAG, "Starting TUN: fd=$fd, ipv6=$ipv6Enabled, stack=gvisor")
            Bridge.nativeStartTun(
                fd = fd,
                stack = "gvisor",
                gateway = gateway,
                portal = portal,
                dns = dns,
                tunInterface = tunInterface
            )
            tunActive = true
            true
        } catch (e: Throwable) {
            tunActive = false
            Log.e(TAG, "nativeStartTun failed", e)
            false
        }
    }

    fun stopTun() {
        if (!tunActive) return
        tunActive = false
        try {
            Bridge.nativeStopTun()
        } catch (e: Throwable) {
            Log.w(TAG, "nativeStopTun failed: ${e.message}")
        }
    }

    fun notifyDnsChanged(dnsServers: List<String>) {
        if (!isInitialized) return
        try {
            Bridge.nativeNotifyDnsChanged(dnsServers.joinToString(","))
        } catch (e: Throwable) {
            Log.w(TAG, "nativeNotifyDnsChanged failed: ${e.message}")
        }
    }

    fun getCoreVersion(): String {
        return try {
            Bridge.nativeCoreVersion()
        } catch (_: Throwable) {
            "Mihomo Core (Embedded)"
        }
    }

    fun queryTraffic(): Pair<Long, Long> {
        return try {
            val raw = Bridge.nativeQueryTrafficNow()
            val upRaw = raw ushr 32
            val downRaw = raw and 0xFFFFFFFFL
            Pair(decodeScaledTraffic(upRaw), decodeScaledTraffic(downRaw))
        } catch (_: Throwable) {
            Pair(0L, 0L)
        }
    }

    fun queryTotalTraffic(): Pair<Long, Long> {
        return try {
            val raw = Bridge.nativeQueryTrafficTotal()
            val upRaw = raw ushr 32
            val downRaw = raw and 0xFFFFFFFFL
            Pair(decodeScaledTraffic(upRaw), decodeScaledTraffic(downRaw))
        } catch (_: Throwable) {
            Pair(0L, 0L)
        }
    }

    private fun resolveSocketUid(
        cm: ConnectivityManager?,
        protocol: Int,
        source: String,
        target: String
    ): Int {
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return try {
            val srcAddr = parseInetSocketAddress(source) ?: return 0
            val dstAddr = parseInetSocketAddress(target) ?: return 0
            val uid = cm.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
            if (uid == Process.INVALID_UID) 0 else uid
        } catch (_: Throwable) {
            0
        }
    }

    internal fun parseInetSocketAddress(endpoint: String): InetSocketAddress? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null
        val (host, portStr) = if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx == -1) return null
            val h = trimmed.substring(1, closeIdx)
            val colonIdx = trimmed.indexOf(':', closeIdx)
            if (colonIdx == -1) return null
            h to trimmed.substring(colonIdx + 1)
        } else {
            val colonIdx = trimmed.lastIndexOf(':')
            if (colonIdx <= 0) return null
            trimmed.substring(0, colonIdx) to trimmed.substring(colonIdx + 1)
        }
        val port = portStr.toIntOrNull() ?: return null
        if (port !in 0..65535) return null
        return InetSocketAddress(InetAddress.getByName(host), port)
    }

    internal fun parseLogMessage(raw: String): LogEntry? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val nowText = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        if (trimmed.startsWith("{")) {
            try {
                val obj: JsonObject = json.parseToJsonElement(trimmed) as? JsonObject ?: return null
                val rawLevel = (obj["level"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["type"] as? JsonPrimitive)?.contentOrNull
                    ?: "info"
                val payload = (obj["payload"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
                    ?: trimmed
                val time = (obj["time"] as? JsonPrimitive)?.contentOrNull?.ifBlank { nowText } ?: nowText
                if (payload.isBlank() || payload.contains("Skip once health check")) return null
                return LogEntry(
                    type = normalizeLogLevel(rawLevel),
                    payload = payload.trim(),
                    timeText = time
                )
            } catch (_: Exception) {
                // Fall through to plain text parser
            }
        }

        val bracketMatch = Regex("""^\[?(debug|info|warn|warning|error|err)\]?\s*[:\-]?\s*(.+)$""", RegexOption.IGNORE_CASE)
            .find(trimmed)
        val level = if (bracketMatch != null) normalizeLogLevel(bracketMatch.groupValues[1]) else "INFO"
        val payload = if (bracketMatch != null) bracketMatch.groupValues[2].trim() else trimmed
        if (payload.isEmpty() || payload.contains("Skip once health check")) return null
        return LogEntry(type = level, payload = payload, timeText = nowText)
    }

    private fun normalizeLogLevel(raw: String): String {
        return when (raw.trim().lowercase()) {
            "err", "error", "fatal" -> "ERROR"
            "warn", "warning" -> "WARN"
            "debug" -> "DEBUG"
            else -> "INFO"
        }
    }

    private fun decodeScaledTraffic(v: Long): Long {
        val tag = ((v ushr 30) and 0x3L).toInt()
        val value = v and 0x3FFFFFFFL
        return when (tag) {
            3 -> (value shl 30) / 100L
            2 -> (value shl 20) / 100L
            1 -> (value shl 10) / 100L
            else -> value
        }
    }

    private fun copyAssetIfNeeded(
        context: Context,
        assetName: String,
        destFile: File,
        overwrite: Boolean = false
    ): Boolean {
        if (!overwrite && destFile.exists() && destFile.length() > 0) return true
        return try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "copyAssetIfNeeded($assetName) failed: ${e.message}")
            destFile.exists() && destFile.length() > 0
        }
    }

    private fun getDefaultConfig(): String {
        return """
port: 7890
socks-port: 7891
mixed-port: 7892
allow-lan: false
mode: rule
log-level: info
unified-delay: true
tcp-concurrent: true
dns:
  enable: true
  ipv6: false
  default-nameserver: [223.5.5.5, 119.29.29.29]
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  nameserver:
    - https://dns.alidns.com/dns-query
    - https://doh.pub/dns-query
proxies: []
proxy-groups:
  - name: 🚀 节点选择
    type: select
    proxies:
      - DIRECT
rules:
  - MATCH,🚀 节点选择
""".trimIndent()
    }
}
