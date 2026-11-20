package com.clashflux.android.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clashflux.android.core.MihomoControllerClient
import com.clashflux.android.model.ProxyMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clash_flux_settings"
)

enum class PerAppMode(val label: String) {
    ALL("全部应用走代理"),
    BYPASS_SELECTED("绕过已选应用 (黑名单)"),
    PROXY_SELECTED("仅代理已选应用 (白名单)")
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式")
}

data class AppSettings(
    val allowLan: Boolean = false,
    val ipv6Enabled: Boolean = false,
    val startOnBoot: Boolean = false,
    val bypassLan: Boolean = true,
    val logLevel: String = "info",
    val proxyMode: ProxyMode = ProxyMode.Rule,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val perAppPackages: Set<String> = emptySet(),
    val hideSystemApps: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val mixedPort: Int = SettingsRepository.DEFAULT_MIXED_PORT,
    val httpPort: Int = SettingsRepository.DEFAULT_HTTP_PORT,
    val socksPort: Int = SettingsRepository.DEFAULT_SOCKS_PORT,
    val autoUpdateProfiles: Boolean = false
)

data class VpnRouteSnapshot(
    val perAppMode: PerAppMode,
    val perAppPackages: Set<String>,
    val ipv6Enabled: Boolean,
    val bypassLan: Boolean
) {
    fun differsFrom(settings: AppSettings): Boolean =
        perAppMode != settings.perAppMode ||
            perAppPackages != settings.perAppPackages ||
            ipv6Enabled != settings.ipv6Enabled ||
            bypassLan != settings.bypassLan
}

interface SettingsStore {
    val settingsFlow: Flow<AppSettings>
    suspend fun getSnapshot(): AppSettings
    suspend fun setAllowLan(enabled: Boolean)
    suspend fun setIpv6Enabled(enabled: Boolean)
    suspend fun setStartOnBoot(enabled: Boolean)
    suspend fun setBypassLan(enabled: Boolean)
    suspend fun setLogLevel(level: String)
    suspend fun setProxyMode(mode: ProxyMode)
    suspend fun setProxyPorts(mixedPort: Int, httpPort: Int, socksPort: Int)
    suspend fun setAutoUpdateProfiles(enabled: Boolean)
    suspend fun setPerAppMode(mode: PerAppMode)
    suspend fun togglePerAppPackage(packageName: String)
    suspend fun setPerAppPackages(packages: Set<String>)
    suspend fun setHideSystemApps(hide: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
}

class SettingsRepository internal constructor(private val dataStore: DataStore<Preferences>) : SettingsStore {

    private constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    private object Keys {
        val ALLOW_LAN = booleanPreferencesKey("allow_lan")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val PROXY_MODE = stringPreferencesKey("proxy_mode")
        val PER_APP_MODE = stringPreferencesKey("per_app_mode")
        val PER_APP_PACKAGES = stringSetPreferencesKey("per_app_packages")
        val HIDE_SYSTEM_APPS = booleanPreferencesKey("hide_system_apps")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val MIXED_PORT = intPreferencesKey("mixed_port")
        val HTTP_PORT = intPreferencesKey("http_port")
        val SOCKS_PORT = intPreferencesKey("socks_port")
        val AUTO_UPDATE_PROFILES = booleanPreferencesKey("auto_update_profiles")
    }

    override val settingsFlow: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs.toAppSettings() }

    override suspend fun getSnapshot(): AppSettings = settingsFlow.first()

    override suspend fun setAllowLan(enabled: Boolean) = updatePref(Keys.ALLOW_LAN, enabled)

    override suspend fun setIpv6Enabled(enabled: Boolean) = updatePref(Keys.IPV6_ENABLED, enabled)

    override suspend fun setStartOnBoot(enabled: Boolean) = updatePref(Keys.START_ON_BOOT, enabled)

    override suspend fun setBypassLan(enabled: Boolean) = updatePref(Keys.BYPASS_LAN, enabled)

    override suspend fun setLogLevel(level: String) {
        val normalized = normalizeLogLevelSetting(level)
        updatePref(Keys.LOG_LEVEL, normalized)
    }

    override suspend fun setProxyMode(mode: ProxyMode) = updatePref(Keys.PROXY_MODE, mode.name)

    override suspend fun setPerAppMode(mode: PerAppMode) = updatePref(Keys.PER_APP_MODE, mode.name)

    override suspend fun setHideSystemApps(hide: Boolean) = updatePref(Keys.HIDE_SYSTEM_APPS, hide)

    override suspend fun setThemeMode(mode: ThemeMode) = updatePref(Keys.THEME_MODE, mode.name)

    override suspend fun setDynamicColor(enabled: Boolean) = updatePref(Keys.DYNAMIC_COLOR, enabled)

    override suspend fun setAutoUpdateProfiles(enabled: Boolean) = updatePref(Keys.AUTO_UPDATE_PROFILES, enabled)

    override suspend fun setProxyPorts(mixedPort: Int, httpPort: Int, socksPort: Int) {
        val safeMixed = normalizePort(mixedPort, DEFAULT_MIXED_PORT)
        val safeHttp = normalizePort(httpPort, DEFAULT_HTTP_PORT)
        val safeSocks = normalizePort(socksPort, DEFAULT_SOCKS_PORT)
        dataStore.edit { prefs ->
            prefs[Keys.MIXED_PORT] = safeMixed
            prefs[Keys.HTTP_PORT] = safeHttp
            prefs[Keys.SOCKS_PORT] = safeSocks
        }
    }

    override suspend fun setPerAppPackages(packages: Set<String>) {
        dataStore.edit { prefs ->
            prefs[Keys.PER_APP_PACKAGES] = packages
        }
    }

    override suspend fun togglePerAppPackage(packageName: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.PER_APP_PACKAGES] ?: emptySet()
            prefs[Keys.PER_APP_PACKAGES] = if (packageName in current) {
                current - packageName
            } else {
                current + packageName
            }
        }
    }

    private suspend fun <T> updatePref(key: Preferences.Key<T>, value: T) {
        dataStore.edit { prefs ->
            prefs[key] = value
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val proxyModeName = this[Keys.PROXY_MODE]
        val proxyMode = ProxyMode.entries.firstOrNull { it.name == proxyModeName } ?: ProxyMode.Rule

        val perAppModeName = this[Keys.PER_APP_MODE]
        val perAppMode = PerAppMode.entries.firstOrNull { it.name == perAppModeName } ?: PerAppMode.ALL

        val themeModeName = this[Keys.THEME_MODE]
        val themeMode = ThemeMode.entries.firstOrNull { it.name == themeModeName } ?: ThemeMode.SYSTEM

        val logLevel = normalizeLogLevelSetting(this[Keys.LOG_LEVEL])

        return AppSettings(
            allowLan = this[Keys.ALLOW_LAN] ?: false,
            ipv6Enabled = this[Keys.IPV6_ENABLED] ?: false,
            startOnBoot = this[Keys.START_ON_BOOT] ?: false,
            bypassLan = this[Keys.BYPASS_LAN] ?: true,
            logLevel = logLevel,
            proxyMode = proxyMode,
            perAppMode = perAppMode,
            perAppPackages = this[Keys.PER_APP_PACKAGES] ?: emptySet(),
            hideSystemApps = this[Keys.HIDE_SYSTEM_APPS] ?: true,
            themeMode = themeMode,
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: false,
            mixedPort = normalizePort(this[Keys.MIXED_PORT] ?: DEFAULT_MIXED_PORT, DEFAULT_MIXED_PORT),
            httpPort = normalizePort(this[Keys.HTTP_PORT] ?: DEFAULT_HTTP_PORT, DEFAULT_HTTP_PORT),
            socksPort = normalizePort(this[Keys.SOCKS_PORT] ?: DEFAULT_SOCKS_PORT, DEFAULT_SOCKS_PORT),
            autoUpdateProfiles = this[Keys.AUTO_UPDATE_PROFILES] ?: false
        )
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 7890
        const val DEFAULT_SOCKS_PORT = 7891
        const val DEFAULT_MIXED_PORT = 7892
        val VALID_LOG_LEVELS = listOf("silent", "error", "warning", "info", "debug")

        fun normalizePort(port: Int, fallback: Int): Int {
            return if (port in 1024..65535 && port != MihomoControllerClient.CONTROLLER_PORT) {
                port
            } else {
                fallback
            }
        }

        fun normalizeLogLevelSetting(raw: String?): String {
            val clean = raw?.trim()?.lowercase()
            return if (clean in VALID_LOG_LEVELS) clean!! else "info"
        }

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
