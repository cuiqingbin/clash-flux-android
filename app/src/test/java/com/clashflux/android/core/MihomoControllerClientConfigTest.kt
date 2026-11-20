package com.clashflux.android.core

import com.clashflux.android.data.AppSettings
import com.clashflux.android.model.ProxyMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoControllerClientConfigTest {

    @Test
    fun buildOverrideJson_includesConfiguredPortsAllowLanIpv6AndDnsIpv6() {
        val altMode = ProxyMode.entries.firstOrNull { it != ProxyMode.Rule } ?: ProxyMode.Rule
        val settings = AppSettings(
            allowLan = true,
            ipv6Enabled = true,
            logLevel = "debug",
            proxyMode = altMode,
            mixedPort = 9002,
            httpPort = 9000,
            socksPort = 9001
        )

        val root = Json.parseToJsonElement(MihomoControllerClient.buildOverrideJson(settings)).jsonObject

        assertEquals(MihomoControllerClient.CONTROLLER_ADDRESS, root["external-controller"]!!.jsonPrimitive.content)
        assertTrue(root["secret"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(true, root["allow-lan"]!!.jsonPrimitive.boolean)
        assertEquals(true, root["ipv6"]!!.jsonPrimitive.boolean)
        assertEquals("debug", root["log-level"]!!.jsonPrimitive.content)
        assertEquals(altMode.coreValue, root["mode"]!!.jsonPrimitive.content)
        assertEquals(9002, root["mixed-port"]!!.jsonPrimitive.int)
        assertEquals(9000, root["port"]!!.jsonPrimitive.int)
        assertEquals(9001, root["socks-port"]!!.jsonPrimitive.int)
        assertEquals("strict", root["find-process-mode"]!!.jsonPrimitive.content)
        assertEquals(true, root["dns"]!!.jsonObject["ipv6"]!!.jsonPrimitive.boolean)
    }
}
