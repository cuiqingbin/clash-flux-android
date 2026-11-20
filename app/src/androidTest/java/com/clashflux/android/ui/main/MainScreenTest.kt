package com.clashflux.android.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.clashflux.android.model.ConnectionStatus
import com.clashflux.android.model.ProxyMode
import com.clashflux.android.model.TrafficStats
import com.clashflux.android.theme.ClashFluxTheme
import com.clashflux.android.ui.dashboard.DashboardScreen
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysCurrentProfileNodeAndTrafficSummary() {
        composeTestRule.setContent {
            ClashFluxTheme {
                DashboardScreen(
                    status = ConnectionStatus.Connected,
                    currentSpeed = TrafficStats(up = 1024, down = 2048),
                    speedHistory = listOf(TrafficStats(up = 512, down = 1024)),
                    selectedMode = ProxyMode.Rule,
                    currentProfileName = "TestProfile",
                    currentNodeName = "HK-Node-01",
                    onToggleConnection = {},
                    onModeSelected = {},
                    onNavigateToProxies = {},
                    onNavigateToProfiles = {}
                )
            }
        }

        composeTestRule.onNodeWithText("已连接").assertIsDisplayed()
        composeTestRule.onNodeWithText("TestProfile").assertIsDisplayed()
        composeTestRule.onNodeWithText("HK-Node-01").assertIsDisplayed()
    }
}
