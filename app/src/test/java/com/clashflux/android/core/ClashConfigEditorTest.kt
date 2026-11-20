package com.clashflux.android.core

import com.clashflux.android.data.ProfileRepository
import com.clashflux.android.model.ClashRule
import com.clashflux.android.model.TrafficStats
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class ClashConfigEditorTest {

    @Test
    fun `saveProxies preserves nested maps and reconciles proxy groups`() {
        val baseYaml = VlessParser.parseToClashYaml(
            listOf(
                "vless://11111111-2222-3333-4444-555555555555@a.example.com:443?security=reality&pbk=PK1&type=ws&path=%2Fws&host=ws.a.com#NodeA",
                "trojan://pass@b.example.com:443#NodeB"
            ).joinToString("\n")
        )

        val extracted = ClashConfigEditor.extractProxies(baseYaml).map { it.toMutableMap() }
        assertEquals(2, extracted.size)

        // Rename NodeA -> NodeA-Renamed and add NodeC while keeping ws-opts & reality-opts intact
        extracted[0]["name"] = "NodeA-Renamed"
        val updatedProxies = extracted + mapOf(
            "name" to "NodeC",
            "type" to "hysteria2",
            "server" to "c.example.com",
            "port" to 443,
            "password" to "p3"
        )

        val savedYaml = ClashConfigEditor.saveProxies(baseYaml, updatedProxies)
        val reExtracted = ClashConfigEditor.extractProxies(savedYaml)
        assertEquals(listOf("NodeA-Renamed", "NodeB", "NodeC"), reExtracted.map { it["name"] })

        val wsOpts = reExtracted[0]["ws-opts"] as? Map<*, *>
        assertNotNull(wsOpts)
        assertEquals("/ws", wsOpts?.get("path"))
        val realityOpts = reExtracted[0]["reality-opts"] as? Map<*, *>
        assertEquals("PK1", realityOpts?.get("public-key"))

        val root = Yaml().load<Map<String, Any>>(savedYaml)
        val groups = root["proxy-groups"] as List<*>
        val selectGroup = groups[0] as Map<*, *>
        val selectEntries = selectGroup["proxies"] as List<*>
        assertTrue(selectEntries.contains("NodeA-Renamed"))
        assertTrue(selectEntries.contains("NodeC"))
        assertTrue(!selectEntries.contains("NodeA"))
    }

    @Test
    fun `compound AND rules round-trip without comma corruption`() {
        val baseYaml = """
            mode: rule
            rules:
              - 'AND,((DOMAIN,a.example.com),(NETWORK,tcp)),DIRECT'
              - DOMAIN-SUFFIX,google.com,PROXY # custom
              - MATCH,DIRECT
        """.trimIndent()

        val parsed = ClashConfigEditor.parseRules(baseYaml)
        assertEquals(3, parsed.size)
        assertEquals("AND", parsed[0].type)
        assertEquals("((DOMAIN,a.example.com),(NETWORK,tcp))", parsed[0].payload)
        assertEquals("DIRECT", parsed[0].policy)

        assertEquals(true, parsed[1].isCustom)
        assertEquals("MATCH", parsed[2].type)

        val resaved = ClashConfigEditor.saveRules(baseYaml, parsed)
        val reparsed = ClashConfigEditor.parseRules(resaved)
        assertEquals(parsed.map { it.type to it.payload }, reparsed.map { it.type to it.payload })
    }

    @Test
    fun `formatBytes uses US decimal separator regardless of system locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("0 B", TrafficStats.formatBytes(0))
            assertEquals("1.5 KB", TrafficStats.formatBytes(1536))
            assertEquals("2.0 MB", TrafficStats.formatBytes(2L * 1024L * 1024L))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `subscription userinfo parser normalizes expire timestamps`() {
        val infoSec = ProfileRepository.parseSubscriptionUserInfo(
            "upload=1024; download=2048; total=10737418240; expire=1790000000"
        )
        assertEquals(1024L, infoSec.uploadBytes)
        assertEquals(2048L, infoSec.downloadBytes)
        assertEquals(10737418240L, infoSec.totalBytes)
        assertEquals(1790000000_000L, infoSec.expireTimestamp)

        val infoMs = ProfileRepository.parseSubscriptionUserInfo("expire=1790000000000")
        assertEquals(1790000000000L, infoMs.expireTimestamp)
    }

    @Test
    fun `prepareRuntimeYaml prepends LAN bypass rules when enabled`() {
        val source = """
            mode: rule
            rules:
              - DOMAIN-SUFFIX,example.com,PROXY
              - MATCH,PROXY
        """.trimIndent()

        val runtime = ProfileRepository.prepareRuntimeYaml(source, bypassLan = true)
        val rules = ClashConfigEditor.parseRules(runtime)
        assertTrue(rules.any { it.type == "IP-CIDR" && it.payload == "192.168.0.0/16" && it.policy == "DIRECT" })
        assertTrue(rules.any { it.type == "GEOIP" && it.payload == "lan" && it.policy == "DIRECT" })
        // Original input string remains unmodified
        assertTrue(!source.contains("192.168.0.0/16"))
    }

    @Test
    fun `validateYamlSyntax accepts valid mapping and rejects blank scalar or broken syntax`() {
        val validYaml = """
            mode: rule
            proxies: []
            rules:
              - MATCH,DIRECT
        """.trimIndent()
        assertNull(ClashConfigEditor.validateYamlSyntax(validYaml))

        assertNotNull(ClashConfigEditor.validateYamlSyntax("   "))
        assertNotNull(ClashConfigEditor.validateYamlSyntax("just a plain string"))
        assertNotNull(ClashConfigEditor.validateYamlSyntax("proxies:\n  - name: [unclosed"))
    }

    @Test
    fun `mergeCustomRules prepends custom rules without duplicating incoming rules`() {
        val incomingYaml = """
            mode: rule
            rules:
              - DOMAIN,dup.example.com,DIRECT
              - MATCH,PROXY
        """.trimIndent()
        val customRules = listOf(
            ClashRule(type = "DOMAIN-SUFFIX", payload = "mycustom.org", policy = "DIRECT", isCustom = true),
            ClashRule(type = "DOMAIN", payload = "dup.example.com", policy = "DIRECT", isCustom = true)
        )

        val merged = ClashConfigEditor.mergeCustomRules(incomingYaml, customRules)
        val parsed = ClashConfigEditor.parseRules(merged)
        assertEquals(3, parsed.size)
        assertEquals("mycustom.org", parsed[0].payload)
        assertTrue(parsed[0].isCustom)
        assertEquals("dup.example.com", parsed[1].payload)
        assertEquals("MATCH", parsed[2].type)
    }

    @Test
    fun `normalizeSubscriptionRules preserves whitelist rules without injecting GEOSITE google`() {
        val whitelistYaml = """
            mode: rule
            rules:
              - DOMAIN-SUFFIX,google.com,Proxy
              - GEOSITE,cn,DIRECT
              - MATCH,DIRECT
        """.trimIndent()

        val normalized = ClashConfigEditor.normalizeSubscriptionRules(whitelistYaml)
        val parsed = ClashConfigEditor.parseRules(normalized)

        assertTrue(parsed.none { it.type == "GEOSITE" && it.payload.equals("google", ignoreCase = true) })
        assertTrue(parsed.any { it.type == "DOMAIN-SUFFIX" && it.payload == "google.com" && it.policy == "Proxy" })
        assertEquals(whitelistYaml, normalized)
    }

    @Test
    fun `normalizeSubscriptionRules deduplicates legacy google suffixes when GEOSITE google already exists`() {
        val mixedYaml = """
            mode: rule
            rules:
              - DOMAIN-SUFFIX,google.com,Proxy
              - DOMAIN-SUFFIX,googleapis.com,Proxy # custom
              - GEOSITE,google,Proxy
              - MATCH,DIRECT
        """.trimIndent()

        val normalized = ClashConfigEditor.normalizeSubscriptionRules(mixedYaml)
        val parsed = ClashConfigEditor.parseRules(normalized)

        assertTrue(parsed.none { it.type == "DOMAIN-SUFFIX" && it.payload == "google.com" })
        assertTrue(parsed.any { it.type == "DOMAIN-SUFFIX" && it.payload == "googleapis.com" && it.isCustom })
        assertTrue(parsed.any { it.type == "GEOSITE" && it.payload == "google" })
    }

    @Test
    fun `extractProxies normalizes unquoted yaml dates to ISO-8601 strings`() {
        val yamlWithDate = """
            proxies:
              - name: DateNode
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-128-gcm
                password: pwd
                expire-date: 2026-12-31
        """.trimIndent()

        val proxies = ClashConfigEditor.extractProxies(yamlWithDate)
        assertEquals(1, proxies.size)
        val expireVal = proxies.single()["expire-date"]
        assertTrue(expireVal is String)
        assertTrue(expireVal.toString().startsWith("2026-12-31"))
    }
}
