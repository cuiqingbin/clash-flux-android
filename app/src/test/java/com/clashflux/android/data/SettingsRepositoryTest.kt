package com.clashflux.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.clashflux.android.core.MihomoControllerClient
import com.clashflux.android.model.ProxyMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultSettings_matchExpectedBaseline() = runTest {
        val storeFile = File(tempFolder.root, "defaults.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { storeFile }
        )
        val repo = SettingsRepository(dataStore)

        val settings = repo.settingsFlow.first()
        assertFalse(settings.allowLan)
        assertFalse(settings.ipv6Enabled)
        assertFalse(settings.startOnBoot)
        assertTrue(settings.bypassLan)
        assertEquals("info", settings.logLevel)
        assertEquals(ProxyMode.Rule, settings.proxyMode)
        assertEquals(PerAppMode.ALL, settings.perAppMode)
        assertEquals(emptySet<String>(), settings.perAppPackages)
        assertTrue(settings.hideSystemApps)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.dynamicColor)
        assertEquals(SettingsRepository.DEFAULT_MIXED_PORT, settings.mixedPort)
        assertEquals(SettingsRepository.DEFAULT_HTTP_PORT, settings.httpPort)
        assertEquals(SettingsRepository.DEFAULT_SOCKS_PORT, settings.socksPort)
        assertFalse(settings.autoUpdateProfiles)
    }

    @Test
    fun persistedUpdates_roundTripAcrossRepositoryMethods() = runTest {
        val storeFile = File(tempFolder.root, "roundtrip.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { storeFile }
        )
        val repo = SettingsRepository(dataStore)

        repo.setAllowLan(true)
        repo.setIpv6Enabled(true)
        repo.setStartOnBoot(true)
        repo.setBypassLan(false)
        repo.setLogLevel("  DEBUG ")
        repo.setProxyMode(ProxyMode.Global)
        repo.setProxyPorts(mixedPort = 17892, httpPort = 17890, socksPort = 17891)
        repo.setAutoUpdateProfiles(true)
        repo.setPerAppMode(PerAppMode.PROXY_SELECTED)
        repo.setPerAppPackages(setOf("com.example.a", "com.example.b"))
        repo.togglePerAppPackage("com.example.b")
        repo.togglePerAppPackage("com.example.c")
        repo.setHideSystemApps(false)
        repo.setThemeMode(ThemeMode.DARK)
        repo.setDynamicColor(true)

        val snapshot = repo.getSnapshot()
        assertTrue(snapshot.allowLan)
        assertTrue(snapshot.ipv6Enabled)
        assertTrue(snapshot.startOnBoot)
        assertFalse(snapshot.bypassLan)
        assertEquals("debug", snapshot.logLevel)
        assertEquals(ProxyMode.Global, snapshot.proxyMode)
        assertEquals(17892, snapshot.mixedPort)
        assertEquals(17890, snapshot.httpPort)
        assertEquals(17891, snapshot.socksPort)
        assertTrue(snapshot.autoUpdateProfiles)
        assertEquals(PerAppMode.PROXY_SELECTED, snapshot.perAppMode)
        assertEquals(setOf("com.example.a", "com.example.c"), snapshot.perAppPackages)
        assertFalse(snapshot.hideSystemApps)
        assertEquals(ThemeMode.DARK, snapshot.themeMode)
        assertTrue(snapshot.dynamicColor)
    }

    @Test
    fun normalizeHelpers_rejectInvalidPortsAndUnknownLogLevels() {
        assertEquals(
            SettingsRepository.DEFAULT_MIXED_PORT,
            SettingsRepository.normalizePort(80, SettingsRepository.DEFAULT_MIXED_PORT)
        )
        assertEquals(
            SettingsRepository.DEFAULT_HTTP_PORT,
            SettingsRepository.normalizePort(70000, SettingsRepository.DEFAULT_HTTP_PORT)
        )
        assertEquals(
            SettingsRepository.DEFAULT_SOCKS_PORT,
            SettingsRepository.normalizePort(
                MihomoControllerClient.CONTROLLER_PORT,
                SettingsRepository.DEFAULT_SOCKS_PORT
            )
        )
        assertEquals(
            18080,
            SettingsRepository.normalizePort(18080, SettingsRepository.DEFAULT_HTTP_PORT)
        )

        assertEquals("warning", SettingsRepository.normalizeLogLevelSetting(" WARNING "))
        assertEquals("info", SettingsRepository.normalizeLogLevelSetting("trace"))
        assertEquals("info", SettingsRepository.normalizeLogLevelSetting(null))
    }

    @Test
    fun vpnRouteSnapshot_differsFrom_ignoresNonRouteSettingsAndDetectsRouteDrift() {
        val base = AppSettings(
            perAppMode = PerAppMode.BYPASS_SELECTED,
            perAppPackages = setOf("com.example.app"),
            ipv6Enabled = true,
            bypassLan = true
        )
        val snapshot = VpnRouteSnapshot(
            perAppMode = PerAppMode.BYPASS_SELECTED,
            perAppPackages = setOf("com.example.app"),
            ipv6Enabled = true,
            bypassLan = true
        )

        assertFalse(
            snapshot.differsFrom(
                base.copy(
                    allowLan = true,
                    logLevel = "debug",
                    mixedPort = 17892,
                    themeMode = ThemeMode.DARK
                )
            )
        )
        assertTrue(snapshot.differsFrom(base.copy(ipv6Enabled = false)))
        assertTrue(snapshot.differsFrom(base.copy(bypassLan = false)))
        assertTrue(snapshot.differsFrom(base.copy(perAppMode = PerAppMode.ALL)))
        assertTrue(snapshot.differsFrom(base.copy(perAppPackages = emptySet())))
    }
}
