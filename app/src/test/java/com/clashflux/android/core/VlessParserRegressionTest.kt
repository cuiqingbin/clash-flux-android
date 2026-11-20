package com.clashflux.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessParserRegressionTest {

    @Test
    fun percentDecodeUtf8_preservesLiteralPlusSignsAndMalformedEscapes() {
        assertEquals("Tokyo+Node +1%", VlessParser.percentDecodeUtf8("Tokyo+Node%20%2B1%25"))
        assertEquals("中文+节点 100%", VlessParser.percentDecodeUtf8("%E4%B8%AD%E6%96%87+%E8%8A%82%E7%82%B9%20100%25"))
        assertEquals("keep+raw%ZZ%2", VlessParser.percentDecodeUtf8("keep+raw%ZZ%2"))
    }

    @Test
    fun parseVless_preservesLiteralPlusInRemarksAndQueryValues() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?security=tls&sni=cdn.example.com&type=ws&path=%2Fws+plus%2F1#HK+01%20BGP"

        val yaml = VlessParser.parseToClashYaml(uri)
        val proxies = ClashConfigEditor.extractProxies(yaml)

        assertEquals(1, proxies.size)
        val node = proxies.first()
        assertEquals("HK+01 BGP", node["name"])
        assertEquals("cdn.example.com", node["servername"])
        @Suppress("UNCHECKED_CAST")
        val wsOpts = node["ws-opts"] as Map<String, Any>
        assertEquals("/ws+plus/1", wsOpts["path"])
    }

    @Test
    fun parseShadowsocks_parsesSip002PluginOptsAndPlusInBase64Password() {
        // aes-128-gcm:password+1 -> YWVzLTEyOC1nY206cGFzc3dvcmQrMQ==
        val uri = "ss://YWVzLTEyOC1nY206cGFzc3dvcmQrMQ==@example.com:8388" +
            "?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dcdn.example.com#SS+Plugin"

        val yaml = VlessParser.parseToClashYaml(uri)
        val proxies = ClashConfigEditor.extractProxies(yaml)

        assertEquals(1, proxies.size)
        val node = proxies.first()
        assertEquals("SS+Plugin", node["name"])
        assertEquals("ss", node["type"])
        assertEquals("aes-128-gcm", node["cipher"])
        assertEquals("password+1", node["password"])
        assertEquals("obfs", node["plugin"])
        @Suppress("UNCHECKED_CAST")
        val pluginOpts = node["plugin-opts"] as Map<String, Any>
        assertEquals("http", pluginOpts["mode"])
        assertEquals("cdn.example.com", pluginOpts["host"])
    }

    @Test
    fun parseShadowsocks_parsesV2rayPluginFlagsAndPercentEncodedUserInfoFallback() {
        val uri = "ss://chacha20-ietf-poly1305:my%2Bpass@example.com:443" +
            "?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Btls%3Bskip-cert-verify%3D1%3Bhost%3Dws.example.com#SS+WS"

        val yaml = VlessParser.parseToClashYaml(uri)
        val proxies = ClashConfigEditor.extractProxies(yaml)

        assertEquals(1, proxies.size)
        val node = proxies.first()
        assertEquals("chacha20-ietf-poly1305", node["cipher"])
        assertEquals("my+pass", node["password"])
        assertEquals("v2ray-plugin", node["plugin"])
        @Suppress("UNCHECKED_CAST")
        val pluginOpts = node["plugin-opts"] as Map<String, Any>
        assertEquals("websocket", pluginOpts["mode"])
        assertEquals(true, pluginOpts["tls"])
        assertEquals(true, pluginOpts["skip-cert-verify"])
        assertEquals("ws.example.com", pluginOpts["host"])
    }

    @Test
    fun parseHysteria2_parsesPortHoppingAndNonNumericIntervalSafely() {
        val numericUri = "hy2://secret%2Bpwd@example.com:443" +
            "?mport=10000-20000&hop-interval=30&sni=hy.example.com&insecure=1#HY2+Hop"
        val nonNumericUri = "hy2://pwd@example.com:443?mport=5000,6000&hop-interval=abc#HY2+Raw"

        val yaml = VlessParser.parseToClashYaml("$numericUri\n$nonNumericUri")
        val proxies = ClashConfigEditor.extractProxies(yaml)

        assertEquals(2, proxies.size)
        val first = proxies[0]
        assertEquals("HY2+Hop", first["name"])
        assertEquals("hysteria2", first["type"])
        assertEquals("secret+pwd", first["password"])
        assertEquals("10000-20000", first["ports"])
        assertEquals(30, first["hop-interval"])
        assertEquals(true, first["skip-cert-verify"])

        val second = proxies[1]
        assertEquals("5000,6000", second["ports"])
        assertTrue(second["hop-interval"].toString() == "abc")
    }

    @Test
    fun parseVless_keepsEncodedAmpersandInSingleParamAndPasswordHashBeforeAt() {
        val singleParamUri = "vless://uuid-1@example.com:443?type=ws&path=%2Ffoo%26bar#SingleParam"
        val multiDoubleEncodedUri = "vless://uuid-2@example.com:8443?security=tls%26type=ws%26sni=a.example.com#DoubleEnc"
        val passWithEncodedHashUri = "trojan://pass%23word@example.com:443#NoFragment"

        val singleNode = ClashConfigEditor.extractProxies(VlessParser.parseToClashYaml(singleParamUri)).single()
        @Suppress("UNCHECKED_CAST")
        val wsOpts = singleNode["ws-opts"] as Map<String, Any>
        assertEquals("/foo&bar", wsOpts["path"])

        val doubleNode = ClashConfigEditor.extractProxies(VlessParser.parseToClashYaml(multiDoubleEncodedUri)).single()
        assertEquals("ws", doubleNode["network"])
        assertEquals(true, doubleNode["tls"])
        assertEquals("a.example.com", doubleNode["servername"])

        val trojanNode = ClashConfigEditor.extractProxies(VlessParser.parseToClashYaml(passWithEncodedHashUri)).single()
        assertEquals("pass#word", trojanNode["password"])
    }

    @Test
    fun parseHostPort_rejectsInvalidZeroOverflowAndNonNumericPorts() {
        val badPortZero = "vless://1111@example.com:0#BadZero"
        val badPortOverflow = "trojan://pass@example.com:65536#BadMax"
        val badPortAlpha = "hy2://pass@example.com:abc#BadAlpha"
        val emptyHost = "vless://1111@:443#EmptyHost"

        assertEquals("", VlessParser.parseToClashYaml(badPortZero))
        assertEquals("", VlessParser.parseToClashYaml(badPortOverflow))
        assertEquals("", VlessParser.parseToClashYaml(badPortAlpha))
        assertEquals("", VlessParser.parseToClashYaml(emptyHost))
    }
}
