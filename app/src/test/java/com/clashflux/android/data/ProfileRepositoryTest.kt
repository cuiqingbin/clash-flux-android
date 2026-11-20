package com.clashflux.android.data

import com.clashflux.android.core.ClashConfigEditor
import com.clashflux.android.model.ClashRule
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `importLocalYamlProfile saves yaml selects profile and supports deletion`() = runBlocking {
        val repo = ProfileRepository(tempFolder.newFolder("repo_local"))
        val yaml = """
            mode: rule
            proxy-providers:
              sub:
                type: http
                url: https://example.com/provider.yaml
            proxy-groups:
              - name: Auto
                type: url-test
                use: [sub]
            rules:
              - MATCH,Auto
        """.trimIndent()

        val imported = repo.importLocalYamlProfile("测试本地配置", yaml).getOrThrow()
        assertEquals(1, imported.first.size)
        val firstProfile = imported.second
        assertEquals("测试本地配置", firstProfile.name)
        assertEquals("", firstProfile.url)
        assertTrue(firstProfile.isSelected)
        assertTrue(File(firstProfile.filePath).exists())

        val second = repo.addProfile("单节点", "trojan://pass123@node.example.com:443#Node1").getOrThrow()
        assertEquals(2, second.first.size)
        assertTrue(second.second.isSelected)

        val (reselected, activeAfterSelect) = repo.selectProfile(firstProfile.id)
        assertEquals(firstProfile.id, activeAfterSelect?.id)
        assertFalse(reselected.first { it.id == second.second.id }.isSelected)

        val (afterDelete, nextActive) = repo.deleteProfile(firstProfile.id)
        assertEquals(1, afterDelete.size)
        assertEquals(second.second.id, nextActive?.id)
        assertFalse(File(firstProfile.filePath).exists())
        assertNull(repo.writeProfileYaml("missing-id", yaml))
    }

    @Test
    fun `updateProfile fetches upstream config and preserves custom rules`() = runBlocking {
        var currentBody = """
            mode: rule
            proxies:
              - name: NodeA
                type: trojan
                server: a.example.com
                port: 443
                password: p1
            proxy-groups:
              - name: Proxy
                type: select
                proxies: [NodeA, DIRECT]
            rules:
              - DOMAIN-SUFFIX,example.com,Proxy
              - MATCH,Proxy
        """.trimIndent()
        var currentUserInfo = "upload=100; download=200; total=1000; expire=1790000000"

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub.yaml") { exchange ->
            val bytes = currentBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/yaml; charset=utf-8")
            exchange.responseHeaders.add("Subscription-Userinfo", currentUserInfo)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val repo = ProfileRepository(tempFolder.newFolder("repo_http"))
            val url = "http://127.0.0.1:${server.address.port}/sub.yaml"
            val (_, initial) = repo.addProfile("机场订阅", url).getOrThrow()
            assertEquals(100L, initial.uploadBytes)
            assertEquals(200L, initial.downloadBytes)

            val initialYaml = repo.readProfileYaml(initial.id)
            val withCustom = ClashConfigEditor.saveRules(
                initialYaml,
                listOf(ClashRule(type = "DOMAIN-SUFFIX", payload = "internal.corp", policy = "DIRECT", isCustom = true)) +
                    ClashConfigEditor.parseRules(initialYaml)
            )
            repo.writeProfileYaml(initial.id, withCustom)

            currentBody = """
                mode: rule
                proxies:
                  - name: NodeB
                    type: trojan
                    server: b.example.com
                    port: 443
                    password: p2
                proxy-groups:
                  - name: Proxy
                    type: select
                    proxies: [NodeB, DIRECT]
                rules:
                  - DOMAIN-SUFFIX,upstream.org,Proxy
                  - MATCH,Proxy
            """.trimIndent()
            currentUserInfo = "upload=300; download=700; total=2000; expire=1795000000"

            val (_, refreshed) = repo.updateProfile(initial.id).getOrThrow()
            assertEquals(300L, refreshed.uploadBytes)
            assertEquals(700L, refreshed.downloadBytes)
            assertEquals(2000L, refreshed.totalBytes)

            val updatedYaml = repo.readProfileYaml(initial.id)
            val rules = ClashConfigEditor.parseRules(updatedYaml)
            assertTrue(rules.any { it.payload == "internal.corp" && it.isCustom })
            assertTrue(rules.any { it.payload == "upstream.org" })
            assertTrue(updatedYaml.contains("NodeB"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `prepareRuntimeYaml injects LAN bypass rules including 172_16_0_0_12 without duplication`() {
        val raw = """
            mode: rule
            rules:
              - DOMAIN-SUFFIX,google.com,Proxy
              - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
              - MATCH,Proxy
        """.trimIndent()

        val result = ProfileRepository.prepareRuntimeYaml(raw, bypassLan = true)
        val parsedRules = ClashConfigEditor.parseRules(result)
        val cidrPayloads = parsedRules.filter { it.type == "IP-CIDR" }.map { it.payload }

        assertTrue("10.0.0.0/8" in cidrPayloads)
        assertTrue("172.16.0.0/12" in cidrPayloads)
        assertTrue("169.254.0.0/16" in cidrPayloads)
        assertEquals(1, cidrPayloads.count { it == "192.168.0.0/16" })
        assertTrue(parsedRules.any { it.type == "GEOIP" && it.payload.equals("lan", ignoreCase = true) })
    }

    @Test
    fun `readBoundedUtf8Stream enforces max byte limit consistently`() {
        val small = "port: 7890\n".byteInputStream()
        assertEquals("port: 7890\n", ProfileRepository.readBoundedUtf8Stream(small, maxBytes = 64L))

        val oversized = ByteArray(1024 * 1024 + 32) { 'a'.code.toByte() }.inputStream()
        val failed = runCatching {
            ProfileRepository.readBoundedUtf8Stream(oversized, maxBytes = 1024L * 1024L, errorLabel = "配置文件")
        }
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("1MB"))
    }
}
