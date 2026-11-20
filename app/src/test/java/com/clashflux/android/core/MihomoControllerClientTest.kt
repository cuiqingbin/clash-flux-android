package com.clashflux.android.core

import com.clashflux.android.data.AppSettings
import com.clashflux.android.model.ProxyMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MihomoControllerClientTest {

    @Test
    fun `buildOverrideJson includes controller secret and settings`() {
        val jsonStr = MihomoControllerClient.buildOverrideJson(
            AppSettings(
                allowLan = true,
                ipv6Enabled = true,
                proxyMode = ProxyMode.Global,
                logLevel = "debug"
            )
        )
        val root = Json.parseToJsonElement(jsonStr).jsonObject
        assertEquals("127.0.0.1:9090", root["external-controller"]?.jsonPrimitive?.content)
        assertEquals(MihomoControllerClient.sessionSecret, root["secret"]?.jsonPrimitive?.content)
        assertEquals("true", root["allow-lan"]?.jsonPrimitive?.content)
        assertEquals("true", root["ipv6"]?.jsonPrimitive?.content)
        assertEquals("global", root["mode"]?.jsonPrimitive?.content)
        assertEquals("debug", root["log-level"]?.jsonPrimitive?.content)
        assertEquals("strict", root["find-process-mode"]?.jsonPrimitive?.content)
        val dns = root["dns"]?.jsonObject
        assertEquals("true", dns?.get("ipv6")?.jsonPrimitive?.content)
    }

    @Test
    fun `parseConnectionsJson extracts mihomo connection fields and skips invalid entries`() {
        val payload = """
            {
              "downloadTotal": 4096,
              "uploadTotal": 1024,
              "connections": [
                {
                  "id": "conn-1",
                  "metadata": {
                    "network": "tcp",
                    "type": "HTTP",
                    "sourceIP": "192.168.1.10",
                    "sourcePort": "54321",
                    "host": "api.github.com",
                    "remoteDestination": "140.82.114.6",
                    "destinationPort": "443",
                    "process": "com.android.chrome",
                    "processPath": "/data/app/chrome",
                    "uid": 10248
                  },
                  "upload": 512,
                  "download": 2048,
                  "start": "2026-11-13T10:15:30.123456Z",
                  "chains": ["🚀 节点选择", "HK-01"],
                  "rule": "DOMAIN-SUFFIX",
                  "rulePayload": "github.com"
                },
                {
                  "id": ""
                }
              ]
            }
        """.trimIndent()

        val items = MihomoControllerClient.parseConnectionsJson(payload)
        assertEquals(1, items.size)
        val first = items.single()
        assertEquals("conn-1", first.id)
        assertEquals("tcp", first.metadata.network)
        assertEquals("api.github.com", first.metadata.host)
        assertEquals("140.82.114.6", first.metadata.destinationIP)
        assertEquals("443", first.metadata.destinationPort)
        assertEquals(512L, first.upload)
        assertEquals(2048L, first.download)
        assertEquals("10:15:30", first.start)
        assertEquals(listOf("🚀 节点选择", "HK-01"), first.chains)
        assertEquals("DOMAIN-SUFFIX", first.rule)
        assertEquals("github.com", first.rulePayload)
        assertEquals("com.android.chrome", first.metadata.process)
        assertEquals("/data/app/chrome", first.metadata.processPath)
        assertEquals(10248, first.metadata.uid)
    }

    @Test
    fun `parseProxiesJson extracts selector groups provider nodes and latest delay`() {
        val payload = """
            {
              "proxies": {
                "🚀 节点选择": {
                  "name": "🚀 节点选择",
                  "type": "Selector",
                  "now": "HK-01",
                  "all": ["HK-01", "JP-02", "DIRECT"]
                },
                "HK-01": {
                  "name": "HK-01",
                  "type": "Vless",
                  "history": [
                    { "time": "2026-11-13T10:00:00Z", "delay": 210 },
                    { "time": "2026-11-13T10:05:00Z", "delay": 86 }
                  ]
                },
                "JP-02": {
                  "name": "JP-02",
                  "type": "Trojan",
                  "history": [
                    { "time": "2026-11-13T10:05:00Z", "delay": 0 }
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = MihomoControllerClient.parseProxiesJson(payload)
        assertEquals(3, parsed.size)
        val group = parsed["🚀 节点选择"]
        assertNotNull(group)
        assertEquals("Selector", group?.type)
        assertEquals("HK-01", group?.now)
        assertEquals(listOf("HK-01", "JP-02", "DIRECT"), group?.all)
        assertEquals(86, parsed["HK-01"]?.delay)
        assertEquals(-1, parsed["JP-02"]?.delay)
    }
}
