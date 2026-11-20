package com.clashflux.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashConfigEditorRuntimeTest {

    @Test
    fun ensureMobileRuntimeDefaults_stripsDesktopTunAndInjectsConfiguredPortsAndDns() {
        val desktopYaml = """
            tun:
              enable: true
              stack: system
              auto-route: true
              auto-detect-interface: true
            proxies:
              - name: Node-1
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-128-gcm
                password: secret
        """.trimIndent()

        val result = ClashConfigEditor.ensureMobileRuntimeDefaults(
            yamlContent = desktopYaml,
            mixedPort = 8892,
            httpPort = 8890,
            socksPort = 8891
        )

        assertFalse(result.lines().any { it.trimStart().startsWith("tun:") })
        assertFalse(result.contains("auto-detect-interface"))
        assertTrue(result.contains("port: 8890"))
        assertTrue(result.contains("socks-port: 8891"))
        assertTrue(result.contains("mixed-port: 8892"))
        assertTrue(result.contains("dns:"))
        assertTrue(result.contains("enhanced-mode: fake-ip"))
        assertTrue(result.contains("Node-1"))
        assertEquals(null, ClashConfigEditor.validateYamlSyntax(result))
    }

    @Test
    fun ensureMobileRuntimeDefaults_preservesExistingPortsAndDnsWithoutDuplicates() {
        val existingYaml = """
            port: 7000
            socks-port: 7001
            mixed-port: 7002
            dns:
              enable: false
            proxies:
              - name: Node-A
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-128-gcm
                password: secret
        """.trimIndent()

        val result = ClashConfigEditor.ensureMobileRuntimeDefaults(
            yamlContent = existingYaml,
            mixedPort = 9992,
            httpPort = 9990,
            socksPort = 9991
        )

        assertEquals(1, Regex("(?m)^port:").findAll(result).count())
        assertEquals(1, Regex("(?m)^socks-port:").findAll(result).count())
        assertEquals(1, Regex("(?m)^mixed-port:").findAll(result).count())
        assertEquals(1, Regex("(?m)^dns:").findAll(result).count())
        assertTrue(result.contains("port: 7000"))
        assertTrue(result.contains("socks-port: 7001"))
        assertTrue(result.contains("mixed-port: 7002"))
        assertFalse(result.contains("mixed-port: 9992"))
    }

    @Test
    fun ensureMobileRuntimeDefaults_handlesBlankYamlGracefully() {
        assertTrue(ClashConfigEditor.ensureMobileRuntimeDefaults("   \n\t  ").isBlank())
        assertEquals("", ClashConfigEditor.ensureMobileRuntimeDefaults(""))
    }
}
