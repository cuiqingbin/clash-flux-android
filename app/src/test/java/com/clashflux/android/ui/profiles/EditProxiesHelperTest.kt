package com.clashflux.android.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditProxiesHelperTest {

    @Test
    fun buildEditedProxyMap_supportsShadowsocksCipherAndVmessAlterIdAndTransport() {
        val ssRes = buildEditedProxyMap(
            input = ProxyFormInput(
                name = "SS Node",
                type = "ss",
                server = "1.2.3.4",
                port = "8388",
                credential = "secret",
                cipher = "2022-blake3-aes-128-gcm"
            ),
            initialProxy = null
        ).getOrThrow()

        assertEquals("ss", ssRes["type"])
        assertEquals("2022-blake3-aes-128-gcm", ssRes["cipher"])
        assertEquals("secret", ssRes["password"])

        val vmessRes = buildEditedProxyMap(
            input = ProxyFormInput(
                name = "VMess WS",
                type = "vmess",
                server = "vm.example.com",
                port = "443",
                credential = "11111111-2222-3333-4444-555555555555",
                cipher = "chacha20-poly1305",
                alterId = "4",
                network = "ws",
                wsPath = "/ws",
                wsHost = "cdn.example.com",
                sni = "sni.example.com",
                tlsEnabled = true
            ),
            initialProxy = null
        ).getOrThrow()

        assertEquals(4, vmessRes["alterId"])
        assertEquals("ws", vmessRes["network"])
        @Suppress("UNCHECKED_CAST")
        val wsOpts = vmessRes["ws-opts"] as Map<String, Any>
        assertEquals("/ws", wsOpts["path"])
        @Suppress("UNCHECKED_CAST")
        val headers = wsOpts["headers"] as Map<String, Any>
        assertEquals("cdn.example.com", headers["Host"])
        assertEquals("sni.example.com", vmessRes["servername"])
    }

    @Test
    fun buildEditedProxyMap_preservesNestedUnknownFieldsAndAllowsOptionalSocksAuth() {
        val initialVless = mapOf(
            "name" to "Reality",
            "type" to "vless",
            "server" to "old.example.com",
            "port" to 443,
            "uuid" to "uuid-old",
            "reality-opts" to mapOf("public-key" to "PK123", "short-id" to "ab"),
            "ws-opts" to mapOf("path" to "/old", "headers" to mapOf("X-Custom" to "keep"))
        )

        val updatedVless = buildEditedProxyMap(
            input = ProxyFormInput(
                name = "Reality Updated",
                type = "vless",
                server = "new.example.com",
                port = "8443",
                credential = "uuid-new",
                network = "ws",
                wsPath = "/new",
                wsHost = "edge.example.com"
            ),
            initialProxy = initialVless
        ).getOrThrow()

        assertTrue(updatedVless.containsKey("reality-opts"))
        @Suppress("UNCHECKED_CAST")
        val wsOpts = updatedVless["ws-opts"] as Map<String, Any>
        assertEquals("/new", wsOpts["path"])
        @Suppress("UNCHECKED_CAST")
        val headers = wsOpts["headers"] as Map<String, Any>
        assertEquals("keep", headers["X-Custom"])
        assertEquals("edge.example.com", headers["Host"])

        val openSocks = buildEditedProxyMap(
            input = ProxyFormInput(
                name = "Local Socks",
                type = "socks5",
                server = "127.0.0.1",
                port = "1080",
                credential = ""
            ),
            initialProxy = null
        ).getOrThrow()
        assertEquals("socks5", openSocks["type"])
        assertFalse(openSocks.containsKey("password"))
        assertFalse(openSocks.containsKey("username"))
    }
}
