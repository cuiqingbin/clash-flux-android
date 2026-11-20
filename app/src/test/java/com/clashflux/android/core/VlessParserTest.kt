package com.clashflux.android.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class VlessParserTest {

    @Test
    fun `parses vless reality uri with nested reality-opts`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?encryption=none&security=reality&sni=cdn.example.com&fp=firefox&pbk=PUBKEY123&sid=ab12&type=tcp#Reality%20HK"

        val yamlText = VlessParser.parseToClashYaml(uri)
        assertTrue(yamlText.isNotBlank())

        val proxies = ClashConfigEditor.extractProxies(yamlText)
        assertEquals(1, proxies.size)
        val node = proxies.first()
        assertEquals("Reality HK", node["name"])
        assertEquals("vless", node["type"])
        assertEquals("example.com", node["server"])
        assertEquals(443, node["port"])
        assertEquals("cdn.example.com", node["servername"])
        assertEquals("firefox", node["client-fingerprint"])
        assertEquals(true, node["tls"])

        val realityOpts = node["reality-opts"] as? Map<*, *>
        assertNotNull(realityOpts)
        assertEquals("PUBKEY123", realityOpts?.get("public-key"))
        assertEquals("ab12", realityOpts?.get("short-id"))
    }

    @Test
    fun `parses vless websocket uri with nested ws-opts headers`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@edge.example.com:8443" +
            "?security=tls&type=ws&path=%2Ftunnel&host=ws.example.com#WSNode"

        val yamlText = VlessParser.parseToClashYaml(uri)
        val node = ClashConfigEditor.extractProxies(yamlText).single()
        val wsOpts = node["ws-opts"] as? Map<*, *>
        assertNotNull(wsOpts)
        assertEquals("/tunnel", wsOpts?.get("path"))
        val headers = wsOpts?.get("headers") as? Map<*, *>
        assertEquals("ws.example.com", headers?.get("Host"))
    }

    @Test
    fun `parses vmess json preserving distinct sni and ws host`() {
        val vmessJson = """
            {
              "v": "2",
              "ps": "VMess-HK",
              "add": "1.2.3.4",
              "port": 443,
              "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "aid": 0,
              "net": "ws",
              "type": "none",
              "host": "ws-host.example.com",
              "path": "/vmess",
              "tls": "tls",
              "sni": "tls-sni.example.com"
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8))

        val yamlText = VlessParser.parseToClashYaml("vmess://$encoded")
        val node = ClashConfigEditor.extractProxies(yamlText).single()
        assertEquals("VMess-HK", node["name"])
        assertEquals("vmess", node["type"])
        assertEquals("tls-sni.example.com", node["servername"])
        val wsOpts = node["ws-opts"] as? Map<*, *>
        val headers = wsOpts?.get("headers") as? Map<*, *>
        assertEquals("ws-host.example.com", headers?.get("Host"))
    }

    @Test
    fun `parses trojan shadowsocks and hysteria2 schemes`() {
        val ssCreds = Base64.getEncoder().encodeToString("aes-256-gcm:sspass".toByteArray(StandardCharsets.UTF_8))
        val subText = listOf(
            "trojan://trpass@trojan.example.com:443?sni=sni.trojan.test&allowInsecure=1#TrojanNode",
            "ss://$ssCreds@ss.example.com:8388#SSNode",
            "hysteria2://hypass@hy.example.com:443?sni=hy.sni&obfs=salamander&obfs-password=opass&pinSHA256=sha256pin&alpn=h3#HY2Node",
            "hy2://hy2pass@hy2.example.com:8443?insecure=true#HY2Short"
        ).joinToString("\n")

        val yamlText = VlessParser.parseToClashYaml(subText)
        val proxies = ClashConfigEditor.extractProxies(yamlText)
        assertEquals(4, proxies.size)

        val trojan = proxies[0]
        assertEquals("trojan", trojan["type"])
        assertEquals("trpass", trojan["password"])
        assertEquals("sni.trojan.test", trojan["sni"])
        assertEquals(true, trojan["skip-cert-verify"])

        val ss = proxies[1]
        assertEquals("ss", ss["type"])
        assertEquals("aes-256-gcm", ss["cipher"])
        assertEquals("sspass", ss["password"])

        val hy2 = proxies[2]
        assertEquals("hysteria2", hy2["type"])
        assertEquals("hypass", hy2["password"])
        assertEquals("salamander", hy2["obfs"])
        assertEquals("opass", hy2["obfs-password"])
        assertEquals("sha256pin", hy2["fingerprint"])
        assertEquals(listOf("h3"), hy2["alpn"])

        val hy2Short = proxies[3]
        assertEquals("hysteria2", hy2Short["type"])
        assertEquals(true, hy2Short["skip-cert-verify"])
    }

    @Test
    fun `decodes base64 batch subscription and deduplicates names`() {
        val raw = listOf(
            "trojan://p1@a.example.com:443#HK",
            "trojan://p2@b.example.com:443#HK",
            "hy2://p3@c.example.com:443#JP"
        ).joinToString("\n")
        val b64 = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))

        val yamlText = VlessParser.parseToClashYaml(b64)
        val proxies = ClashConfigEditor.extractProxies(yamlText)
        assertEquals(listOf("HK", "HK (2)", "JP"), proxies.map { it["name"] })

        val root = Yaml().load<Map<String, Any>>(yamlText)
        val groups = root["proxy-groups"] as? List<*>
        val firstGroup = groups?.firstOrNull() as? Map<*, *>
        val groupProxies = firstGroup?.get("proxies") as? List<*>
        assertTrue(groupProxies?.containsAll(listOf("HK", "HK (2)", "JP")) == true)
    }

    @Test
    fun `isClashYaml recognizes pure proxy-providers config and preserves yaml`() {
        val providerConfig = """
            mode: rule
            proxy-providers:
              airport:
                type: http
                url: https://example.com/sub.yaml
                interval: 3600
            proxy-groups:
              - name: AutoSelect
                type: url-test
                use:
                  - airport
            rules:
              - MATCH,AutoSelect
        """.trimIndent()

        assertTrue(VlessParser.isClashYaml(providerConfig))
        assertFalse(VlessParser.isClashYaml("vless://1111@a.com:443#Node"))
        assertEquals(providerConfig, VlessParser.parseToClashYaml(providerConfig))
    }

    @Test
    fun `parses tuic wireguard and socks5 schemes while keeping http urls distinct`() {
        assertFalse(VlessParser.isNodeUri("https://example.com/sub.yaml"))
        assertFalse(VlessParser.isNodeUri("http://127.0.0.1:8080/sub"))

        val userPassB64 = Base64.getEncoder().encodeToString("alice:secret".toByteArray(StandardCharsets.UTF_8))
        val subText = listOf(
            "tuic://11111111-2222-3333-4444-555555555555:tuicpass@tuic.example.com:8443?alpn=h3,h2&congestion_control=bbr&udp_relay_mode=quic&sni=tuic.sni&allow_insecure=1#TUICNode",
            "wireguard://privKey123=@wg.example.com:51820?publickey=pubKey456=&address=10.0.0.2/32,fd00::2/128&mtu=1420&reserved=1,2,3#WGNode",
            "socks://$userPassB64@socks.example.com:1080#SocksAuth",
            "socks5://127.0.0.1:1088#SocksPlain"
        ).joinToString("\n")

        val yamlText = VlessParser.parseToClashYaml(subText)
        val proxies = ClashConfigEditor.extractProxies(yamlText)
        assertEquals(4, proxies.size)

        val tuic = proxies[0]
        assertEquals("tuic", tuic["type"])
        assertEquals("11111111-2222-3333-4444-555555555555", tuic["uuid"])
        assertEquals("tuicpass", tuic["password"])
        assertEquals(listOf("h3", "h2"), tuic["alpn"])
        assertEquals("bbr", tuic["congestion-controller"])
        assertEquals("quic", tuic["udp-relay-mode"])
        assertEquals(true, tuic["skip-cert-verify"])

        val wg = proxies[1]
        assertEquals("wireguard", wg["type"])
        assertEquals("privKey123=", wg["private-key"])
        assertEquals("pubKey456=", wg["public-key"])
        assertEquals("10.0.0.2", wg["ip"])
        assertEquals("fd00::2", wg["ipv6"])
        assertEquals(1420, wg["mtu"])
        assertEquals(listOf(1, 2, 3), wg["reserved"])

        val socksAuth = proxies[2]
        assertEquals("socks5", socksAuth["type"])
        assertEquals("alice", socksAuth["username"])
        assertEquals("secret", socksAuth["password"])
        assertEquals(1080, socksAuth["port"])

        val socksPlain = proxies[3]
        assertEquals("socks5", socksPlain["type"])
        assertEquals("127.0.0.1", socksPlain["server"])
        assertEquals(1088, socksPlain["port"])
        assertFalse(socksPlain.containsKey("username"))
    }
}
