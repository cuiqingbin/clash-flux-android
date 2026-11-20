package com.clashflux.android.ui.main

import com.clashflux.android.core.MihomoControllerClient
import com.clashflux.android.core.YamlProxyGroupParser
import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.PerAppMode
import com.clashflux.android.data.ProfileStore
import com.clashflux.android.data.SettingsRepository
import com.clashflux.android.data.SettingsStore
import com.clashflux.android.data.ThemeMode
import com.clashflux.android.data.VpnRouteSnapshot
import com.clashflux.android.model.ConnectionItem
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.LogEntry
import com.clashflux.android.model.Profile
import com.clashflux.android.model.ProxyMode
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainCoordinatorTest {

    private val sampleYaml = """
        mixed-port: 7892
        proxies:
          - name: NodeA
            type: ss
            server: 10.0.0.2
            port: 8388
            cipher: aes-128-gcm
            password: pass
          - name: NodeB
            type: ss
            server: 10.0.0.3
            port: 8389
            cipher: aes-128-gcm
            password: pass
        proxy-groups:
          - name: Proxy
            type: select
            proxies:
              - Auto
              - NodeA
              - DIRECT
          - name: Auto
            type: url-test
            proxies:
              - NodeB
        rules:
          - MATCH,Proxy
    """.trimIndent()

    @Test
    fun setProxyPorts_validatesRangeAndDistinctnessBeforePersisting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore()
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env
        )

        val outOfRange = coordinator.setProxyPorts(80, 7890, 7891)
        assertTrue(outOfRange.isFailure)
        assertTrue(outOfRange.exceptionOrNull()?.message.orEmpty().contains("1024"))

        val duplicate = coordinator.setProxyPorts(7892, 7892, 7891)
        assertTrue(duplicate.isFailure)
        assertTrue(duplicate.exceptionOrNull()?.message.orEmpty().contains("不能重复"))

        val controllerConflict = coordinator.setProxyPorts(
            MihomoControllerClient.CONTROLLER_PORT,
            7890,
            7891
        )
        assertTrue(controllerConflict.isFailure)
        assertTrue(
            controllerConflict.exceptionOrNull()?.message.orEmpty()
                .contains(MihomoControllerClient.CONTROLLER_PORT.toString())
        )
        assertEquals(
            SettingsRepository.DEFAULT_MIXED_PORT,
            SettingsRepository.normalizePort(
                MihomoControllerClient.CONTROLLER_PORT,
                SettingsRepository.DEFAULT_MIXED_PORT
            )
        )

        val valid = coordinator.setProxyPorts(8892, 8890, 8891)
        assertTrue(valid.isSuccess)
        assertEquals(8892, settingsStore.getSnapshot().mixedPort)
        assertEquals(8890, settingsStore.getSnapshot().httpPort)
        assertEquals(8891, settingsStore.getSnapshot().socksPort)
        assertEquals(1, env.appliedSettings.size)
        assertEquals(8892, env.appliedSettings.single().mixedPort)
    }

    @Test
    fun addProfile_keepsErrorOnFailureAndAppliesCoreOnSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore().apply {
            nextAddFailure = IllegalStateException("网络超时")
        }
        val env = FakeMainRuntimeEnvironment()
        val files = mutableMapOf<String, String>()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { path -> path in files },
            readFileText = { path -> files[path].orEmpty() }
        )

        val failed = coordinator.addProfile("Fail", "https://example.com/sub")
        assertTrue(failed.isFailure)
        assertEquals("网络超时", failed.exceptionOrNull()?.message)
        assertFalse(coordinator.isAddingProfile.value)
        assertTrue(env.loadedConfigPaths.isEmpty())

        profileStore.nextAddFailure = null
        profileStore.yamlByIdWriter = { path, yaml -> files[path] = yaml }
        profileStore.nextYaml = sampleYaml

        val succeeded = coordinator.addProfile("Good", "https://example.com/ok")
        assertTrue(succeeded.isSuccess)
        assertFalse(coordinator.isAddingProfile.value)
        assertEquals(1, coordinator.profiles.value.size)
        assertEquals(1, env.loadedConfigPaths.size)
    }

    @Test
    fun deleteProfile_refusesDeletionWhileUpdating() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profile = Profile(
            id = "p1",
            name = "Sub",
            url = "https://example.com/sub",
            filePath = "/fake/p1.yaml",
            isSelected = true
        )
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore(initialProfiles = listOf(profile), initialYamls = mapOf("p1" to sampleYaml))
        val updateGate = CompletableDeferred<Unit>()
        profileStore.updateGate = updateGate
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { sampleYaml }
        )
        coordinator.loadSavedProfiles()
        runCurrent()

        coordinator.updateProfile("p1")
        runCurrent()
        assertTrue("p1" in coordinator.busyProfileIds.value)

        coordinator.deleteProfile("p1")
        runCurrent()
        assertEquals(1, coordinator.profiles.value.size)
        assertTrue(env.toasts.any { it.contains("正在更新中") })

        updateGate.complete(Unit)
        runCurrent()
        assertFalse("p1" in coordinator.busyProfileIds.value)
    }

    @Test
    fun restoreProfileBackup_restoresYamlAndReloadsSelectedCoreConfig() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profile = Profile(
            id = "p1",
            name = "BackupTest",
            url = "",
            filePath = "/fake/p1.yaml",
            isSelected = true
        )
        val files = mutableMapOf("/fake/p1.yaml" to sampleYaml)
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(profile),
            initialYamls = mapOf("p1" to sampleYaml),
            initialBackups = mapOf("p1" to sampleYaml.replace("NodeA", "NodeRestored"))
        )
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { path -> path in files },
            readFileText = { path -> profileStore.currentYaml(path) }
        )
        coordinator.loadSavedProfiles()
        runCurrent()
        env.loadedConfigPaths.clear()

        assertTrue(coordinator.hasProfileBackup("p1"))
        assertTrue(coordinator.restoreProfileBackup("p1"))
        assertFalse(coordinator.hasProfileBackup("p1"))
        assertEquals(listOf("/fake/p1.yaml"), env.loadedConfigPaths)
        assertTrue(coordinator.proxyGroups.value.first().all.any { it.name == "NodeRestored" })
    }

    @Test
    fun testGroupDelay_deduplicatesAndFallsBackToDirectTcpAndSubGroupRollup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profile = Profile(
            id = "p1",
            name = "DelayTest",
            url = "",
            filePath = "/fake/p1.yaml",
            isSelected = true
        )
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(profile),
            initialYamls = mapOf("p1" to sampleYaml)
        )
        val groupGate = CompletableDeferred<Map<String, Int>?>()
        val env = FakeMainRuntimeEnvironment().apply {
            directDelayResult = 18
            tcpConnectDelays["10.0.0.3:8389"] = 77
        }
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { sampleYaml }
        )
        coordinator.loadSavedProfiles()
        runCurrent()
        env.groupDelayCalls.clear()
        env.testGroupDelayHandler = { groupName ->
            if (groupName == "Proxy") groupGate.await() else emptyMap()
        }

        // First call starts test; second call and node call for same active group must be deduplicated
        coordinator.testGroupDelay("Proxy")
        runCurrent()
        coordinator.testGroupDelay("Proxy")
        coordinator.testNodeDelay("Proxy", "NodeA")
        runCurrent()

        assertTrue(coordinator.proxyGroups.value.first { it.name == "Proxy" }.all.all { it.isTesting })

        // Controller returns NodeA=42 and NodeB=77 (sub-group Auto points to NodeB -> rolls up 77 to Auto!)
        groupGate.complete(mapOf("NodeA" to 42, "NodeB" to 77))
        runCurrent()

        assertEquals(listOf("Proxy"), env.groupDelayCalls)
        val proxyGroup = coordinator.proxyGroups.value.first { it.name == "Proxy" }
        assertEquals(42, proxyGroup.all.first { it.name == "NodeA" }.delay)
        assertEquals(18, proxyGroup.all.first { it.name == "DIRECT" }.delay)
        assertEquals(77, proxyGroup.all.first { it.name == "Auto" }.delay)
        assertTrue(proxyGroup.all.none { it.isTesting })
    }

    @Test
    fun start_autoUpdatesOnlyStaleRemoteProfilesWhenEnabled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val now = 1_000_000_000_000L
        val staleRemote = Profile(
            id = "stale",
            name = "StaleRemote",
            url = "https://example.com/stale",
            filePath = "/fake/stale.yaml",
            updatedAt = now - MainCoordinator.AUTO_UPDATE_INTERVAL_MS - 1_000L,
            isSelected = false
        )
        val freshRemote = Profile(
            id = "fresh",
            name = "FreshRemote",
            url = "https://example.com/fresh",
            filePath = "/fake/fresh.yaml",
            updatedAt = now - 60_000L,
            isSelected = true
        )
        val staleLocal = Profile(
            id = "local",
            name = "LocalOnly",
            url = "",
            filePath = "/fake/local.yaml",
            updatedAt = now - MainCoordinator.AUTO_UPDATE_INTERVAL_MS - 1_000L,
            isSelected = false
        )
        val settingsStore = FakeSettingsStore(AppSettings(autoUpdateProfiles = true))
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(staleRemote, freshRemote, staleLocal),
            initialYamls = mapOf("stale" to sampleYaml, "fresh" to sampleYaml, "local" to sampleYaml)
        )
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { sampleYaml },
            nowMillis = { now }
        )

        coordinator.start(autoLoadSavedProfiles = true)
        runCurrent()

        assertEquals(listOf("stale"), profileStore.updatedIds)
    }

    @Test
    fun connectingWatchdog_stopsVpnAfterTimeoutAndCancelsWhenConnected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore()
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            connectTimeoutMs = 5_000L
        )

        coordinator.start(autoLoadSavedProfiles = false)
        runCurrent()

        env.serviceStateFlow.value = ConnectionStatus.Connecting
        runCurrent()
        advanceTimeBy(4_999L)
        assertEquals(0, env.stopVpnCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, env.stopVpnCalls)
        assertTrue(env.toasts.any { it.contains("连接超时") })

        // Reaching Connected before timeout cancels the watchdog
        env.serviceStateFlow.value = ConnectionStatus.Connecting
        runCurrent()
        advanceTimeBy(2_000L)
        env.serviceStateFlow.value = ConnectionStatus.Connected
        runCurrent()
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(1, env.stopVpnCalls)
    }

    @Test
    fun updateGeoDatabase_togglesBusyFlagAndReportsResultToast() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore()
        val gate = CompletableDeferred<Boolean>()
        val env = FakeMainRuntimeEnvironment().apply {
            geoHandler = { gate.await() }
        }
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env
        )

        coordinator.updateGeoDatabase()
        runCurrent()
        assertTrue(coordinator.isUpdatingGeo.value)

        gate.complete(true)
        runCurrent()
        assertFalse(coordinator.isUpdatingGeo.value)
        assertTrue(env.toasts.any { it.contains("已更新") })
    }

    @Test
    fun start_deduplicatesConnectedWarmupAndCancelsStaleDelaysOnProfileSwitch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val p1 = Profile(id = "p1", name = "First", url = "", filePath = "/fake/p1.yaml", isSelected = true)
        val p2 = Profile(id = "p2", name = "Second", url = "", filePath = "/fake/p2.yaml", isSelected = false)
        val yaml2 = sampleYaml.replace("NodeA", "SecondNode")
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(p1, p2),
            initialYamls = mapOf("p1" to sampleYaml, "p2" to yaml2)
        )
        val env = FakeMainRuntimeEnvironment().apply {
            serviceStateFlow.value = ConnectionStatus.Connected
        }
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { path -> if (path == "/fake/p2.yaml") yaml2 else sampleYaml }
        )

        coordinator.start(autoLoadSavedProfiles = true)
        runCurrent()
        assertEquals(listOf("Proxy"), env.groupDelayCalls)

        // Start a manual delay test on p1, hold it with gate, switch to p2 before gate resolves
        env.groupDelayCalls.clear()
        val staleGate = CompletableDeferred<Map<String, Int>?>()
        var delayHandlerCalls = 0
        env.testGroupDelayHandler = {
            if (delayHandlerCalls++ == 0) {
                staleGate.await()
            } else {
                mapOf("SecondNode" to 55)
            }
        }
        coordinator.testGroupDelay("Proxy")
        runCurrent()

        coordinator.selectProfile("p2")
        runCurrent()
        staleGate.complete(mapOf("NodeA" to 999))
        runCurrent()

        val activeGroup = coordinator.proxyGroups.value.first { it.name == "Proxy" }
        assertTrue(activeGroup.all.any { it.name == "SecondNode" && it.delay == 55 })
        assertTrue(activeGroup.all.none { it.name == "NodeA" })
        assertFalse(activeGroup.all.any { it.isTesting })
    }

    @Test
    fun updateAllRemoteProfiles_updatesConcurrentlyWithoutDroppingProfileUpdates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val r1 = Profile(id = "r1", name = "R1", url = "https://example.com/1", filePath = "/fake/r1.yaml", updatedAt = 0L, isSelected = true)
        val r2 = Profile(id = "r2", name = "R2", url = "https://example.com/2", filePath = "/fake/r2.yaml", updatedAt = 0L, isSelected = false)
        val r3 = Profile(id = "r3", name = "R3", url = "https://example.com/3", filePath = "/fake/r3.yaml", updatedAt = 0L, isSelected = false)
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(r1, r2, r3),
            initialYamls = mapOf("r1" to sampleYaml, "r2" to sampleYaml, "r3" to sampleYaml)
        )
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { sampleYaml }
        )
        coordinator.loadSavedProfiles()
        runCurrent()

        coordinator.updateAllRemoteProfiles()
        runCurrent()

        assertEquals(setOf("r1", "r2", "r3"), profileStore.updatedIds.toSet())
        assertTrue(coordinator.profiles.value.all { it.updatedAt == 1L })
        assertTrue(env.toasts.any { it.contains("全部 3 个订阅更新成功") })
    }

    @Test
    fun saveProfileYaml_rejectsInvalidYamlWithoutOverwritingStore() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val p1 = Profile(id = "p1", name = "P1", url = "", filePath = "/fake/p1.yaml", isSelected = true)
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(p1),
            initialYamls = mapOf("p1" to sampleYaml)
        )
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = FakeSettingsStore(AppSettings()),
            profileStore = profileStore,
            env = env
        )

        val saved = coordinator.saveProfileYaml("p1", "proxies: [unclosed")
        assertFalse(saved)
        assertEquals(sampleYaml, profileStore.readProfileYaml("p1"))
        assertTrue(env.loadedConfigPaths.isEmpty())
        assertTrue(env.toasts.isNotEmpty())
    }

    @Test
    fun saveProfileYaml_rollsBackAndReloadsBackupWhenCoreLoadFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val p1 = Profile(id = "p1", name = "P1", url = "", filePath = "/fake/p1.yaml", isSelected = true)
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(p1),
            initialYamls = mapOf("p1" to sampleYaml)
        )
        val env = FakeMainRuntimeEnvironment()
        var loadCalls = 0
        env.loadCoreConfigHandler = {
            loadCalls++
            loadCalls != 1
        }
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = FakeSettingsStore(AppSettings()),
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { profileStore.currentYaml("/fake/p1.yaml") }
        )

        val editedYaml = "$sampleYaml\n# edited\n"
        val saved = coordinator.saveProfileYaml("p1", editedYaml)

        assertFalse(saved)
        assertEquals(sampleYaml, profileStore.readProfileYaml("p1"))
        assertEquals(listOf("/fake/p1.yaml", "/fake/p1.yaml"), env.loadedConfigPaths)
        assertTrue(env.toasts.any { it.contains("自动恢复") })
    }

    @Test
    fun hasPendingVpnRestart_tracksRouteChangesWhileConnectedAndClearsAfterSnapshotSync() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = FakeSettingsStore(AppSettings()),
            profileStore = FakeProfileStore(),
            env = env
        )
        runCurrent()
        assertFalse(coordinator.hasPendingVpnRestart.value)

        env.serviceStateFlow.value = ConnectionStatus.Connected
        env.appliedRouteSnapshotFlow.value = VpnRouteSnapshot(
            perAppMode = PerAppMode.ALL,
            perAppPackages = emptySet(),
            ipv6Enabled = false,
            bypassLan = true
        )
        runCurrent()
        assertFalse(coordinator.hasPendingVpnRestart.value)

        coordinator.setAllowLan(true)
        runCurrent()
        assertFalse(coordinator.hasPendingVpnRestart.value)

        coordinator.setIpv6Enabled(true)
        runCurrent()
        assertTrue(coordinator.hasPendingVpnRestart.value)

        env.appliedRouteSnapshotFlow.value = VpnRouteSnapshot(
            perAppMode = PerAppMode.ALL,
            perAppPackages = emptySet(),
            ipv6Enabled = true,
            bypassLan = true
        )
        runCurrent()
        assertFalse(coordinator.hasPendingVpnRestart.value)

        coordinator.setBypassLan(false)
        runCurrent()
        assertTrue(coordinator.hasPendingVpnRestart.value)

        env.serviceStateFlow.value = ConnectionStatus.Disconnected
        runCurrent()
        assertFalse(coordinator.hasPendingVpnRestart.value)
    }

    @Test
    fun selectProfile_rapidSwitchesApplyOnlyLatestSelection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val p1 = Profile(id = "p1", name = "First", url = "", filePath = "/fake/p1.yaml", isSelected = false)
        val p2 = Profile(id = "p2", name = "Second", url = "", filePath = "/fake/p2.yaml", isSelected = false)
        val yaml2 = sampleYaml.replace("NodeA", "SecondNode")
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(p1, p2),
            initialYamls = mapOf("p1" to sampleYaml, "p2" to yaml2)
        ).apply {
            selectGates["p1"] = CompletableDeferred()
        }
        val env = FakeMainRuntimeEnvironment()
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { path -> if (path == "/fake/p2.yaml") yaml2 else sampleYaml }
        )

        coordinator.loadSavedProfiles()
        runCurrent()
        env.loadedConfigPaths.clear()

        coordinator.selectProfile("p1")
        runCurrent()

        coordinator.selectProfile("p2")
        runCurrent()

        assertEquals("p2", coordinator.profiles.value.find { it.isSelected }?.id)
        assertEquals(listOf("/fake/p2.yaml"), env.loadedConfigPaths)
    }

    @Test
    fun deleteProfile_stopsRunningVpnWhenLastProfileRemoved() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val p1 = Profile(id = "p1", name = "OnlyProfile", url = "", filePath = "/fake/p1.yaml", isSelected = true)
        val settingsStore = FakeSettingsStore(AppSettings())
        val profileStore = FakeProfileStore(
            initialProfiles = listOf(p1),
            initialYamls = mapOf("p1" to sampleYaml)
        )
        val env = FakeMainRuntimeEnvironment().apply {
            serviceStateFlow.value = ConnectionStatus.Connected
        }
        val coordinator = MainCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            settingsStore = settingsStore,
            profileStore = profileStore,
            env = env,
            fileExists = { true },
            readFileText = { sampleYaml }
        )

        coordinator.loadSavedProfiles()
        runCurrent()

        coordinator.deleteProfile("p1")
        runCurrent()

        assertEquals(1, env.stopVpnCalls)
        assertTrue(coordinator.profiles.value.isEmpty())
        assertTrue(coordinator.proxyGroups.value.isEmpty())
        assertTrue(env.toasts.any { it.contains("已删除最后一份配置") })
    }

    private class FakeSettingsStore(initial: AppSettings) : SettingsStore {
        private val flow = MutableStateFlow(initial)
        override val settingsFlow: Flow<AppSettings> = flow
        override suspend fun getSnapshot(): AppSettings = flow.value
        override suspend fun setAllowLan(enabled: Boolean) { flow.update { it.copy(allowLan = enabled) } }
        override suspend fun setIpv6Enabled(enabled: Boolean) { flow.update { it.copy(ipv6Enabled = enabled) } }
        override suspend fun setStartOnBoot(enabled: Boolean) { flow.update { it.copy(startOnBoot = enabled) } }
        override suspend fun setBypassLan(enabled: Boolean) { flow.update { it.copy(bypassLan = enabled) } }
        override suspend fun setLogLevel(level: String) { flow.update { it.copy(logLevel = level) } }
        override suspend fun setProxyMode(mode: ProxyMode) { flow.update { it.copy(proxyMode = mode) } }
        override suspend fun setProxyPorts(mixedPort: Int, httpPort: Int, socksPort: Int) {
            flow.update { it.copy(mixedPort = mixedPort, httpPort = httpPort, socksPort = socksPort) }
        }
        override suspend fun setAutoUpdateProfiles(enabled: Boolean) {
            flow.update { it.copy(autoUpdateProfiles = enabled) }
        }
        override suspend fun setPerAppMode(mode: PerAppMode) { flow.update { it.copy(perAppMode = mode) } }
        override suspend fun togglePerAppPackage(packageName: String) {
            flow.update { cur ->
                val next = cur.perAppPackages.toMutableSet().apply {
                    if (!add(packageName)) remove(packageName)
                }
                cur.copy(perAppPackages = next)
            }
        }
        override suspend fun setPerAppPackages(packages: Set<String>) {
            flow.update { it.copy(perAppPackages = packages) }
        }
        override suspend fun setHideSystemApps(hide: Boolean) { flow.update { it.copy(hideSystemApps = hide) } }
        override suspend fun setThemeMode(mode: ThemeMode) { flow.update { it.copy(themeMode = mode) } }
        override suspend fun setDynamicColor(enabled: Boolean) { flow.update { it.copy(dynamicColor = enabled) } }
    }

    private class FakeProfileStore(
        initialProfiles: List<Profile> = emptyList(),
        initialYamls: Map<String, String> = emptyMap(),
        initialBackups: Map<String, String> = emptyMap()
    ) : ProfileStore {
        private val list = initialProfiles.toMutableList()
        private val yamls = initialYamls.toMutableMap()
        private val backups = initialBackups.toMutableMap()
        var nextAddFailure: Throwable? = null
        var nextYaml: String = ""
        var yamlByIdWriter: ((String, String) -> Unit)? = null
        var updateGate: CompletableDeferred<Unit>? = null
        val selectGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val updatedIds = mutableListOf<String>()

        fun currentYaml(filePath: String): String {
            val id = list.find { it.filePath == filePath }?.id ?: return ""
            return yamls[id].orEmpty()
        }

        override suspend fun loadProfiles(): List<Profile> = list.toList()

        override suspend fun selectProfile(profileId: String): Pair<List<Profile>, Profile?> {
            selectGates[profileId]?.await()
            for (i in list.indices) {
                list[i] = list[i].copy(isSelected = list[i].id == profileId)
            }
            return list.toList() to list.find { it.isSelected }
        }

        override suspend fun addProfile(nameInput: String, rawInput: String): Result<Pair<List<Profile>, Profile>> {
            nextAddFailure?.let { return Result.failure(it) }
            val id = UUID.randomUUID().toString()
            val path = "/fake/$id.yaml"
            yamls[id] = nextYaml
            yamlByIdWriter?.invoke(path, nextYaml)
            for (i in list.indices) list[i] = list[i].copy(isSelected = false)
            val created = Profile(id = id, name = nameInput, url = rawInput, filePath = path, isSelected = true)
            list.add(created)
            return Result.success(list.toList() to created)
        }

        override suspend fun importLocalYamlProfile(
            nameInput: String,
            yamlText: String
        ): Result<Pair<List<Profile>, Profile>> {
            nextYaml = yamlText
            return addProfile(nameInput, "")
        }

        override suspend fun updateProfile(profileId: String): Result<Pair<List<Profile>, Profile>> {
            updateGate?.await()
            updatedIds.add(profileId)
            val idx = list.indexOfFirst { it.id == profileId }
            if (idx == -1) return Result.failure(IllegalStateException("Missing"))
            val updated = list[idx].copy(updatedAt = list[idx].updatedAt + 1)
            list[idx] = updated
            return Result.success(list.toList() to updated)
        }

        override suspend fun editProfileInfo(profileId: String, name: String, url: String): List<Profile> {
            val idx = list.indexOfFirst { it.id == profileId }
            if (idx != -1) list[idx] = list[idx].copy(name = name, url = url)
            return list.toList()
        }

        override suspend fun deleteProfile(profileId: String): Pair<List<Profile>, Profile?> {
            list.removeAll { it.id == profileId }
            yamls.remove(profileId)
            backups.remove(profileId)
            if (list.isNotEmpty() && list.none { it.isSelected }) {
                list[0] = list[0].copy(isSelected = true)
            }
            return list.toList() to list.find { it.isSelected }
        }

        override suspend fun readProfileYaml(profileId: String): String = yamls[profileId].orEmpty()

        override suspend fun writeProfileYaml(profileId: String, content: String): Profile? {
            val profile = list.find { it.id == profileId } ?: return null
            yamls[profileId]?.let { backups[profileId] = it }
            yamls[profileId] = content
            return profile
        }

        override suspend fun hasProfileBackup(profileId: String): Boolean = profileId in backups

        override suspend fun restoreProfileBackup(profileId: String): Result<Profile> {
            val profile = list.find { it.id == profileId } ?: return Result.failure(IllegalStateException("Missing"))
            val bak = backups.remove(profileId) ?: return Result.failure(IllegalStateException("No backup"))
            yamls[profileId] = bak
            return Result.success(profile)
        }
    }

    private class FakeMainRuntimeEnvironment : MainRuntimeEnvironment {
        val serviceStateFlow = MutableStateFlow(ConnectionStatus.Disconnected)
        val appliedRouteSnapshotFlow = MutableStateFlow<VpnRouteSnapshot?>(null)
        val serviceErrorsFlow = MutableStateFlow<String?>(null)
        val logEventsFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 16)
        override val serviceState: StateFlow<ConnectionStatus> = serviceStateFlow
        override val appliedRouteSnapshot: StateFlow<VpnRouteSnapshot?> = appliedRouteSnapshotFlow
        override val serviceErrors: StateFlow<String?> = serviceErrorsFlow
        override val logEvents: Flow<LogEntry> = logEventsFlow
        override val isVpnRunning: Boolean get() = serviceStateFlow.value != ConnectionStatus.Disconnected

        val loadedConfigPaths = mutableListOf<String>()
        val appliedSettings = mutableListOf<AppSettings>()
        val groupDelayCalls = mutableListOf<String>()
        val tcpConnectDelays = mutableMapOf<String, Int>()
        val toasts = mutableListOf<String>()
        private val selectors = mutableMapOf<String, String>()
        var stopVpnCalls = 0
        var directDelayResult = -1
        var loadCoreConfigHandler: ((String) -> Boolean)? = null
        var testGroupDelayHandler: (suspend (String) -> Map<String, Int>?)? = null
        var geoHandler: (suspend () -> Boolean)? = null

        override fun clearServiceError() { serviceErrorsFlow.value = null }
        override fun ensureTotalTrafficLoaded() = Unit
        override fun startVpn() { serviceStateFlow.value = ConnectionStatus.Connecting }
        override fun stopVpn() {
            stopVpnCalls++
            serviceStateFlow.value = ConnectionStatus.Disconnected
        }
        override fun restartVpn() = Unit
        override suspend fun initCore() = Unit
        override suspend fun loadCoreConfig(path: String): Boolean {
            loadedConfigPaths.add(path)
            return loadCoreConfigHandler?.invoke(path) ?: true
        }
        override suspend fun applySettingsUpdate(settings: AppSettings) {
            appliedSettings.add(settings)
        }
        override suspend fun updateGeoDatabase(): Boolean = geoHandler?.invoke() ?: false
        override fun getSelectorChoice(groupName: String): String? = selectors[groupName]
        override fun putSelectorChoice(groupName: String, nodeName: String) { selectors[groupName] = nodeName }
        override fun patchNativeSelector(groupName: String, nodeName: String) = Unit
        override suspend fun queryLiveProxies(): Map<String, MihomoControllerClient.LiveProxyInfo>? = null
        override suspend fun queryNativeGroupNames(): List<String> = emptyList()
        override suspend fun queryNativeGroupSnapshot(groupName: String): YamlProxyGroupParser.BridgeGroupSnapshot? = null
        override suspend fun selectControllerProxy(groupName: String, nodeName: String): Boolean = true
        override suspend fun testControllerGroupDelay(groupName: String): Map<String, Int>? {
            groupDelayCalls.add(groupName)
            return testGroupDelayHandler?.invoke(groupName)
        }
        override suspend fun testControllerProxyDelay(proxyName: String): Int? = null
        override suspend fun nativeHealthCheckGroup(groupName: String): Map<String, Int> = emptyMap()
        override suspend fun measureDirectHttpDelay(): Int = directDelayResult
        override suspend fun measureTcpConnectDelay(host: String, port: Int): Int {
            return tcpConnectDelays["$host:$port"] ?: -1
        }
        override suspend fun getLiveConnections(): List<ConnectionItem>? = emptyList()
        override suspend fun closeControllerConnection(id: String): Boolean = true
        override suspend fun closeAllControllerConnections(): Boolean = true
        override suspend fun showToast(message: String, long: Boolean) {
            toasts.add(message)
        }
    }
}
