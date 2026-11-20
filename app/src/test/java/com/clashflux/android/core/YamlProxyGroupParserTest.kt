package com.clashflux.android.core

import com.clashflux.android.model.ProxyGroup
import com.clashflux.android.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YamlProxyGroupParserTest {

    @Test
    fun `parse reads groups saved selectors and hostPortMap accurately`() {
        val yaml = """
            proxies:
              - name: HK-01
                type: vless
                server: hk.example.com
                port: 443
              - name: JP-01
                type: trojan
                server: jp.example.com
                port: 8443
            proxy-groups:
              - name: Auto
                type: url-test
                proxies: [HK-01, JP-01]
              - name: Proxy
                type: select
                proxies: [Auto, HK-01, JP-01, DIRECT]
              - name: FallbackSelect
                type: select
                proxies: [DIRECT, JP-01]
        """.trimIndent()

        val parsed = YamlProxyGroupParser.parse(yaml) { groupName ->
            when (groupName) {
                "Proxy" -> "JP-01"
                "FallbackSelect" -> "DeletedNode"
                else -> null
            }
        }

        assertEquals(3, parsed.groups.size)
        assertEquals("hk.example.com" to 443, parsed.hostPortMap["HK-01"])
        assertEquals("jp.example.com" to 8443, parsed.hostPortMap["JP-01"])

        val proxyGroup = parsed.groups.first { it.name == "Proxy" }
        assertEquals("JP-01", proxyGroup.now)
        assertEquals("url-test", proxyGroup.all.first { it.name == "Auto" }.type)
        assertEquals("DIRECT", proxyGroup.all.first { it.name == "DIRECT" }.type)

        // Saved selector 'DeletedNode' is missing from list, so falls back to known proxy JP-01 over DIRECT
        val fallbackSelect = parsed.groups.first { it.name == "FallbackSelect" }
        assertEquals("JP-01", fallbackSelect.now)
    }

    @Test
    fun `parse synthesizes default selector group when YAML lacks proxy-groups`() {
        val text = """
            proxies:
              - name: "Node One"
                type: ss
                server: 1.2.3.4
                port: 8388
        """.trimIndent()

        val parsed = YamlProxyGroupParser.parse(text)
        assertEquals(1, parsed.groups.size)
        val group = parsed.groups.first()
        assertEquals("🚀 节点选择", group.name)
        assertEquals("Node One", group.now)
        assertEquals(listOf("Node One", "DIRECT"), group.all.map { it.name })
        assertEquals("1.2.3.4" to 8388, parsed.hostPortMap["Node One"])
    }

    @Test
    fun `mergeLiveProxies updates delays and discovers groups ignoring GLOBAL`() {
        val current = listOf(
            ProxyGroup(
                name = "Proxy",
                type = "select",
                now = "HK-01",
                all = listOf(
                    ProxyNode(name = "HK-01", type = "vless", delay = null, isTesting = false),
                    ProxyNode(name = "JP-01", type = "trojan", delay = 120, isTesting = true)
                )
            )
        )
        val live = mapOf(
            "GLOBAL" to MihomoControllerClient.LiveProxyInfo("GLOBAL", "select", "Proxy", listOf("Proxy", "DIRECT"), 0),
            "Proxy" to MihomoControllerClient.LiveProxyInfo("Proxy", "select", "JP-01", listOf("HK-01", "JP-01", "DIRECT"), 0),
            "HK-01" to MihomoControllerClient.LiveProxyInfo("HK-01", "vless", "", emptyList(), 48),
            "JP-01" to MihomoControllerClient.LiveProxyInfo("JP-01", "trojan", "", emptyList(), 0),
            "DIRECT" to MihomoControllerClient.LiveProxyInfo("DIRECT", "direct", "", emptyList(), 0)
        )

        val merged = YamlProxyGroupParser.mergeLiveProxies(current, live)
        assertEquals(1, merged.size)
        assertEquals("JP-01", merged.first().now)
        assertEquals(48, merged.first().all.first { it.name == "HK-01" }.delay)
        // Retains prior delay & isTesting when live response has delay == 0
        val jp = merged.first().all.first { it.name == "JP-01" }
        assertEquals(120, jp.delay)
        assertTrue(jp.isTesting)

        // When starting from empty groups, GLOBAL is skipped if other selector groups exist
        val discovered = YamlProxyGroupParser.mergeLiveProxies(emptyList(), live)
        assertEquals(listOf("Proxy"), discovered.map { it.name })
    }

    @Test
    fun `parseBridgeGroupSnapshot and mergeBridgeSnapshots extract delays and current node`() {
        val groupJson = """
            {
              "type": "select",
              "now": "SG-01",
              "proxies": [
                { "name": "SG-01", "type": "vless", "delay": 65 }
              ],
              "all": [
                { "name": "US-01", "type": "vmess", "history": [{ "delay": 180 }] },
                "DIRECT"
              ]
            }
        """.trimIndent()

        val snapshot = YamlProxyGroupParser.parseBridgeGroupSnapshot(groupJson)
        assertNotNull(snapshot)
        assertEquals("SG-01", snapshot!!.now)
        assertEquals(65, snapshot.delays["SG-01"])
        assertEquals(180, snapshot.delays["US-01"])

        val merged = YamlProxyGroupParser.mergeBridgeSnapshots(
            currentGroups = emptyList(),
            groupNames = YamlProxyGroupParser.parseGroupNamesJson("""["Proxy"]"""),
            snapshots = mapOf("Proxy" to snapshot)
        )
        assertEquals(1, merged.size)
        assertEquals("SG-01", merged.first().now)
        assertEquals(3, merged.first().all.size)
    }

    @Test
    fun `selectPrimaryGroup prefers 节点 group then select group then first`() {
        val urlTestFirst = ProxyGroup(name = "Auto", type = "url-test", now = "HK-01", all = emptyList())
        val genericSelect = ProxyGroup(name = "Proxy", type = "select", now = "HK-01", all = emptyList())
        val nodeSelect = ProxyGroup(name = "🚀 节点选择", type = "select", now = "SG-01", all = emptyList())

        assertEquals("🚀 节点选择", YamlProxyGroupParser.selectPrimaryGroup(listOf(urlTestFirst, genericSelect, nodeSelect))?.name)
        assertEquals("Proxy", YamlProxyGroupParser.selectPrimaryGroup(listOf(urlTestFirst, genericSelect))?.name)
        assertEquals("Auto", YamlProxyGroupParser.selectPrimaryGroup(listOf(urlTestFirst))?.name)
        assertEquals(null, YamlProxyGroupParser.selectPrimaryGroup(emptyList()))
    }
}
