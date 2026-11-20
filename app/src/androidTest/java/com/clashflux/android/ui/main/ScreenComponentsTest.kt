package com.clashflux.android.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.clashflux.android.data.AppSettings
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.Profile
import com.clashflux.android.model.ProxyGroup
import com.clashflux.android.model.ProxyMode
import com.clashflux.android.model.ProxyNode
import com.clashflux.android.model.TrafficStats
import com.clashflux.android.theme.ClashFluxTheme
import com.clashflux.android.ui.connections.ConnectionsScreen
import com.clashflux.android.ui.dashboard.DashboardScreen
import com.clashflux.android.ui.profiles.ProfilesScreen
import com.clashflux.android.ui.proxies.ProxiesScreen
import com.clashflux.android.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dashboardScreen_onlyPowerOrbTriggersConnectionToggle_andDisplaysShortcuts() {
        var toggleCount = 0
        composeTestRule.setContent {
            ClashFluxTheme(darkTheme = false, dynamicColor = false) {
                DashboardScreen(
                    status = ConnectionStatus.Disconnected,
                    currentSpeed = TrafficStats(0, 0),
                    speedHistory = emptyList(),
                    selectedMode = ProxyMode.Rule,
                    currentProfileName = "Default",
                    currentNodeName = "HK-01",
                    onToggleConnection = { toggleCount++ },
                    onModeSelected = {},
                    onNavigateToProxies = {},
                    onNavigateToProfiles = {}
                )
            }
        }

        // Clicking outside the power orb on hero card status label must not toggle VPN accidentally
        composeTestRule.onNodeWithText("点击开启代理").performClick()
        assertEquals(0, toggleCount)

        // Clicking the dedicated power orb toggles connection
        composeTestRule.onNodeWithContentDescription("Toggle Connection").performClick()
        assertEquals(1, toggleCount)

        composeTestRule.onNodeWithText("Default").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("HK-01").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun profilesScreen_addDialogStaysOpenWithErrorOnFailureAndClosesOnSuccess() {
        var shouldFail = true
        var updateAllClicks = 0
        val profiles = listOf(
            Profile(
                id = "p1",
                name = "Airport",
                url = "https://example.com/sub",
                filePath = "/tmp/p1.yaml",
                isSelected = true
            )
        )

        composeTestRule.setContent {
            ClashFluxTheme(darkTheme = false, dynamicColor = false) {
                ProfilesScreen(
                    profiles = profiles,
                    onSelectProfile = {},
                    onUpdateProfile = {},
                    onDeleteProfile = {},
                    onUpdateAllProfiles = { updateAllClicks++ },
                    onAddProfile = { _, _ ->
                        if (shouldFail) {
                            Result.failure(IllegalStateException("订阅解析失败"))
                        } else {
                            Result.success(Unit)
                        }
                    }
                )
            }
        }

        composeTestRule.onNodeWithText("更新全部").performClick()
        assertEquals(1, updateAllClicks)

        composeTestRule.onNodeWithContentDescription("Add Profile").performClick()
        composeTestRule.onNodeWithText("添加订阅配置").assertIsDisplayed()
        composeTestRule.onNodeWithText("订阅 URL / 节点链接 / Base64")
            .performTextInput("https://example.com/bad")

        composeTestRule.onNodeWithText("添加并导入").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("订阅解析失败").assertIsDisplayed()
        composeTestRule.onNodeWithText("添加订阅配置").assertIsDisplayed()

        shouldFail = false
        composeTestRule.onNodeWithText("添加并导入").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("添加订阅配置").assertDoesNotExist()
    }

    @Test
    fun proxiesScreen_displaysGroupAndNodeAndHandlesSelection() {
        var selectedPair: Pair<String, String>? = null
        val groups = listOf(
            ProxyGroup(
                name = "Proxy",
                type = "select",
                now = "HK-01",
                all = listOf(
                    ProxyNode(name = "HK-01", type = "vless", delay = 35),
                    ProxyNode(name = "JP-02", type = "trojan", delay = 80)
                )
            )
        )

        composeTestRule.setContent {
            ClashFluxTheme(darkTheme = false, dynamicColor = false) {
                ProxiesScreen(
                    groups = groups,
                    activeProfileId = "p1",
                    onSelectNode = { g, n -> selectedPair = g to n },
                    onTestGroupDelay = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Proxy").assertIsDisplayed()
        composeTestRule.onNodeWithText("JP-02").performClick()
        assertEquals("Proxy" to "JP-02", selectedPair)
    }

    @Test
    fun settingsScreen_displaysPortsOpensDialogAndTriggersGeoUpdate() {
        var geoClicks = 0
        var selectedLogLevel = ""
        val settings = AppSettings(
            mixedPort = 9992,
            httpPort = 9990,
            socksPort = 9991,
            autoUpdateProfiles = true,
            logLevel = "info"
        )

        composeTestRule.setContent {
            ClashFluxTheme(darkTheme = false, dynamicColor = false) {
                SettingsScreen(
                    settings = settings,
                    onNavigateToPerAppProxy = {},
                    onUpdateGeoDatabase = { geoClicks++ },
                    onLogLevelChange = { selectedLogLevel = it }
                )
            }
        }

        composeTestRule.onNodeWithText("启动时自动更新订阅").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("9992 (点击修改)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("9990 / 9991").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithText("更新 GeoIP / GeoSite 数据库").performScrollTo().performClick()
        assertTrue(geoClicks == 1)

        composeTestRule.onNodeWithText("INFO").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("DEBUG").performClick()
        assertEquals("debug", selectedLogLevel)

        composeTestRule.onNodeWithText("混合端口 (Mixed Port)").performScrollTo().performClick()
        composeTestRule.onNodeWithText("保存并应用").assertIsDisplayed()
        composeTestRule.onNodeWithText("取消").performClick()
    }

    @Test
    fun connectionsScreen_displaysTabsAndSwitchesToRealtimeLogs() {
        composeTestRule.setContent {
            ClashFluxTheme(darkTheme = false, dynamicColor = false) {
                ConnectionsScreen(
                    connections = emptyList(),
                    onCloseAll = {},
                    onCloseConnection = {},
                    logs = emptyList()
                )
            }
        }

        composeTestRule.onNodeWithText("连接记录 (0)").assertIsDisplayed()
        composeTestRule.onNodeWithText("暂无连接记录（访问网页后将实时显示）").assertIsDisplayed()

        composeTestRule.onNodeWithText("实时日志 (0)").performClick()
        composeTestRule.onNodeWithText("实时日志 (0)").assertIsDisplayed()
    }
}
