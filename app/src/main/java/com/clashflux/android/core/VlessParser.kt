package com.clashflux.android.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

object VlessParser {

    private val schemes = listOf(
        "vless://",
        "vmess://",
        "ss://",
        "trojan://",
        "hysteria2://",
        "hy2://",
        "tuic://",
        "wireguard://",
        "wg://",
        "socks://",
        "socks5://"
    )

    private data class ParsedUriParts(
        val authorityPart: String,
        val queryParams: Map<String, String>,
        val remark: String
    )

    private data class StandardUri(
        val userInfo: String,
        val server: String,
        val port: Int,
        val queryParams: Map<String, String>,
        val remark: String
    )

    private val doubleEncodedParamRegex = Regex("""^[A-Za-z0-9_-]+=.*$""")

    fun isNodeUri(text: String): Boolean {
        val trimmed = text.trim()
        return schemes.any { trimmed.startsWith(it, ignoreCase = true) } ||
            trimmed.lines().any { line -> schemes.any { line.trim().startsWith(it, ignoreCase = true) } }
    }

    /**
     * Parses single or multiple node lines (or Base64-encoded subscription text) into a valid
     * Mihomo YAML configuration. If [text] is already a Clash YAML configuration, returns it directly.
     */
    fun parseToClashYaml(text: String, defaultName: String = "订阅配置"): String {
        val trimmed = ClashConfigEditor.sanitizeYaml(text).trim()
        if (trimmed.isEmpty()) return ""

        if (isClashYaml(trimmed)) {
            return trimmed
        }

        var rawText = trimmed
        decodeBase64Utf8(trimmed)?.let { decoded ->
            val cleanDecoded = ClashConfigEditor.sanitizeYaml(decoded).trim()
            if (isClashYaml(cleanDecoded)) {
                return cleanDecoded
            }
            if (isNodeUri(cleanDecoded)) {
                rawText = cleanDecoded
            }
        }

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val proxies = mutableListOf<Map<String, Any>>()
        val usedNames = hashMapOf<String, Int>()
        val proxyNames = mutableListOf<String>()

        var index = 1
        for (line in lines) {
            val parsed = parseNode(line, index) ?: continue
            val mutable = parsed.toMutableMap()
            val baseName = (mutable["name"]?.toString() ?: "").ifBlank { "$defaultName $index" }
            val uniqueName = deduplicateName(baseName, usedNames)
            mutable["name"] = uniqueName
            proxies.add(mutable)
            proxyNames.add(uniqueName)
            index++
        }

        if (proxies.isEmpty()) return ""

        val header = buildString {
            appendLine("port: 7890")
            appendLine("socks-port: 7891")
            appendLine("mixed-port: 7892")
            appendLine("allow-lan: false")
            appendLine("mode: rule")
            appendLine("log-level: info")
            appendLine("unified-delay: true")
            appendLine("tcp-concurrent: true")
            appendLine("dns:")
            appendLine("  enable: true")
            appendLine("  ipv6: false")
            appendLine("  enhanced-mode: fake-ip")
            appendLine("  fake-ip-range: 198.18.0.1/16")
            appendLine("  fake-ip-filter:")
            appendLine("    - \"+.lan\"")
            appendLine("    - \"+.local\"")
            appendLine("    - \"localhost.ptlogin2.qq.com\"")
            appendLine("    - \"+.msftconnecttest.com\"")
            appendLine("    - \"+.msftncsi.com\"")
            appendLine("    - \"connectivitycheck.gstatic.com\"")
            appendLine("    - \"+.mtalk.google.com\"")
            appendLine("    - \"mtalk.google.com\"")
            appendLine("    - \"time.*.com\"")
            appendLine("    - \"+.stun.*.*\"")
            appendLine("  default-nameserver: [223.5.5.5, 119.29.29.29]")
            appendLine("  nameserver:")
            appendLine("    - https://dns.alidns.com/dns-query")
            appendLine("    - https://doh.pub/dns-query")
            appendLine("sniffer:")
            appendLine("  enable: true")
            appendLine("  force-dns-mapping: true")
            appendLine("  parse-pure-ip: true")
            appendLine("  override-destination: false")
            appendLine("  sniff:")
            appendLine("    HTTP:")
            appendLine("      ports: [80, 8080-8880]")
            appendLine("      override-destination: true")
            appendLine("    TLS:")
            appendLine("      ports: [443, 8443]")
            appendLine("    QUIC:")
            appendLine("      ports: [443, 8443]")
        }

        val proxiesYaml = ClashConfigEditor.dumpYamlSection("proxies", proxies)
        val proxyGroupsYaml = ClashConfigEditor.dumpYamlSection(
            "proxy-groups",
            listOf(
                linkedMapOf(
                    "name" to "🚀 节点选择",
                    "type" to "select",
                    "proxies" to (proxyNames + listOf("⚡ 自动选择", "DIRECT"))
                ),
                linkedMapOf(
                    "name" to "⚡ 自动选择",
                    "type" to "url-test",
                    "url" to "https://www.gstatic.com/generate_204",
                    "interval" to 300,
                    "proxies" to proxyNames
                )
            )
        )

        val rulesYaml = """
            rules:
              - GEOIP,lan,DIRECT,no-resolve
              - GEOSITE,category-ads-all,REJECT
              - GEOSITE,google,🚀 节点选择
              - GEOSITE,cn,DIRECT
              - GEOIP,CN,DIRECT,no-resolve
              - MATCH,🚀 节点选择
        """.trimIndent()

        return listOf(header.trimEnd(), proxiesYaml, proxyGroupsYaml, rulesYaml).joinToString("\n\n") + "\n"
    }

    fun isClashYaml(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isNodeUri(trimmed)) return false
        val hasProxies = trimmed.contains("proxies:")
        val hasProviders = trimmed.contains("proxy-providers:")
        val hasGroups = trimmed.contains("proxy-groups:")
        val hasRules = trimmed.contains("rules:")
        return (hasProxies && (hasGroups || trimmed.contains("server:"))) ||
            (hasProviders && (hasGroups || hasRules))
    }

    private fun deduplicateName(baseName: String, seenCounts: MutableMap<String, Int>): String {
        val count = seenCounts.getOrDefault(baseName, 0) + 1
        seenCounts[baseName] = count
        return if (count == 1) baseName else "$baseName ($count)"
    }

    private fun parseNode(uriString: String, index: Int): Map<String, Any>? {
        val trimmed = uriString.trim()
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed, index)
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed, index)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed, index)
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, index)
            trimmed.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(trimmed, "hysteria2://", index)
            trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed, "hy2://", index)
            trimmed.startsWith("tuic://", ignoreCase = true) -> parseTuic(trimmed, index)
            trimmed.startsWith("wireguard://", ignoreCase = true) -> parseWireguard(trimmed, "wireguard://", index)
            trimmed.startsWith("wg://", ignoreCase = true) -> parseWireguard(trimmed, "wg://", index)
            trimmed.startsWith("socks5://", ignoreCase = true) -> parseSocks5(trimmed, "socks5://", index)
            trimmed.startsWith("socks://", ignoreCase = true) -> parseSocks5(trimmed, "socks://", index)
            else -> null
        }
    }

    private fun isTruthyParam(value: String?): Boolean {
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    private fun splitUriParts(
        uriString: String,
        schemePrefix: String,
        defaultRemark: String
    ): ParsedUriParts? {
        if (uriString.length <= schemePrefix.length) return null
        val rawBody = uriString.substring(schemePrefix.length)
        val rawHashIdx = rawBody.indexOf('#')
        val (mainPart, remark) = if (rawHashIdx != -1) {
            rawBody.substring(0, rawHashIdx) to
                safeUrlDecode(rawBody.substring(rawHashIdx + 1).trim()).ifEmpty { defaultRemark }
        } else {
            val searchStart = rawBody.indexOf('@').let { if (it >= 0) it + 1 else 0 }
            val encHashIdx = rawBody.indexOf("%23", startIndex = searchStart)
            if (encHashIdx != -1) {
                rawBody.substring(0, encHashIdx) to
                    safeUrlDecode(rawBody.substring(encHashIdx + 3).trim()).ifEmpty { defaultRemark }
            } else {
                rawBody to defaultRemark
            }
        }

        val qIdx = mainPart.indexOf('?')
        val authorityPart = if (qIdx != -1) mainPart.substring(0, qIdx) else mainPart
        val queryParams = if (qIdx != -1) parseQueryParams(mainPart.substring(qIdx + 1)) else emptyMap()
        return ParsedUriParts(authorityPart = authorityPart, queryParams = queryParams, remark = remark)
    }

    /**
     * Parses common `scheme://userInfo@host:port?query#remark` URIs used by VLESS, Trojan, Hysteria2, TUIC, and WireGuard.
     */
    private fun parseStandardUri(
        uriString: String,
        schemePrefix: String,
        defaultRemark: String,
        defaultPort: Int = 443
    ): StandardUri? {
        val parts = splitUriParts(uriString, schemePrefix, defaultRemark) ?: return null
        val atIdx = parts.authorityPart.indexOf('@')
        if (atIdx == -1) return null

        val userInfo = safeUrlDecode(parts.authorityPart.substring(0, atIdx).trim())
        if (userInfo.isEmpty()) return null
        val (server, port) = parseHostPort(parts.authorityPart.substring(atIdx + 1).trim(), defaultPort) ?: return null
        return StandardUri(
            userInfo = userInfo,
            server = server,
            port = port,
            queryParams = parts.queryParams,
            remark = parts.remark
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val effectiveQuery = if (!query.contains('&') && query.contains("%26")) {
            val parts = query.split("%26")
            if (parts.size >= 2 && parts.all { doubleEncodedParamRegex.matches(it) }) {
                parts.joinToString("&")
            } else {
                query
            }
        } else {
            query
        }
        val params = linkedMapOf<String, String>()
        effectiveQuery.split("&").forEach { param ->
            val eqIdx = param.indexOf('=')
            if (eqIdx > 0) {
                val k = param.substring(0, eqIdx).trim().lowercase()
                val v = param.substring(eqIdx + 1).trim()
                params[k] = safeUrlDecode(v)
            }
        }
        return params
    }

    private fun applyTransportOptions(
        map: MutableMap<String, Any>,
        type: String,
        queryParams: Map<String, String>,
        fallbackHost: String?
    ) {
        val path = queryParams["path"]
        val wsHost = queryParams["host"]?.takeIf { it.isNotEmpty() } ?: fallbackHost
        val serviceName = queryParams["servicename"] ?: queryParams["service-name"]

        if (type.equals("ws", ignoreCase = true)) {
            val wsMap = linkedMapOf<String, Any>()
            if (!path.isNullOrEmpty()) wsMap["path"] = path
            if (!wsHost.isNullOrEmpty()) wsMap["headers"] = linkedMapOf("Host" to wsHost)
            map["ws-opts"] = wsMap
        } else if (type.equals("grpc", ignoreCase = true) && !serviceName.isNullOrEmpty()) {
            map["grpc-opts"] = linkedMapOf("grpc-service-name" to serviceName)
        }
    }

    private fun parseVless(uriString: String, index: Int): Map<String, Any>? {
        val parsed = parseStandardUri(uriString, "vless://", "VLESS 节点 $index") ?: return null
        val q = parsed.queryParams
        val type = q["type"] ?: "tcp"
        val encryption = q["encryption"]
        val packetEncoding = q["packetencoding"] ?: q["packet-encoding"] ?: "xudp"
        val security = q["security"] ?: "none"
        val flow = q["flow"]
        val sni = q["sni"] ?: q["host"]
        val fp = q["fp"] ?: "chrome"
        val pbk = q["pbk"]
        val sid = q["sid"]

        val map = linkedMapOf<String, Any>(
            "name" to parsed.remark,
            "type" to "vless",
            "server" to parsed.server,
            "port" to parsed.port,
            "uuid" to parsed.userInfo,
            "udp" to true
        )

        if (!encryption.isNullOrEmpty() && !encryption.equals("none", ignoreCase = true)) {
            map["encryption"] = encryption
        }
        if (packetEncoding.isNotEmpty()) map["packet-encoding"] = packetEncoding
        if (type.isNotEmpty()) map["network"] = type
        if (!flow.isNullOrEmpty()) map["flow"] = flow

        val isTls = security.equals("tls", ignoreCase = true) || security.equals("reality", ignoreCase = true)
        if (isTls) {
            map["tls"] = true
            if (!sni.isNullOrEmpty()) map["servername"] = sni
            if (fp.isNotEmpty()) map["client-fingerprint"] = fp
        }

        if (isTruthyParam(q["allowinsecure"]) || isTruthyParam(q["insecure"])) {
            map["skip-cert-verify"] = true
        }

        if (security.equals("reality", ignoreCase = true) && !pbk.isNullOrEmpty()) {
            val realityMap = linkedMapOf("public-key" to pbk)
            if (!sid.isNullOrEmpty()) realityMap["short-id"] = sid
            map["reality-opts"] = realityMap
        }

        applyTransportOptions(map, type, q, sni)
        return map
    }

    private fun parseTrojan(uriString: String, index: Int): Map<String, Any>? {
        val parsed = parseStandardUri(uriString, "trojan://", "Trojan 节点 $index") ?: return null
        val q = parsed.queryParams
        val sni = q["sni"] ?: q["peer"] ?: parsed.server
        val type = q["type"] ?: "tcp"

        val map = linkedMapOf<String, Any>(
            "name" to parsed.remark,
            "type" to "trojan",
            "server" to parsed.server,
            "port" to parsed.port,
            "password" to parsed.userInfo,
            "udp" to true,
            "sni" to sni
        )
        if (type.isNotEmpty()) map["network"] = type
        if (isTruthyParam(q["allowinsecure"]) || isTruthyParam(q["insecure"])) {
            map["skip-cert-verify"] = true
        }
        q["fp"]?.takeIf { it.isNotEmpty() }?.let { map["client-fingerprint"] = it }
        applyTransportOptions(map, type, q, sni)
        return map
    }

    private fun parseHysteria2(uriString: String, schemePrefix: String, index: Int): Map<String, Any>? {
        val parsed = parseStandardUri(uriString, schemePrefix, "Hysteria2 节点 $index") ?: return null
        val q = parsed.queryParams
        val sni = q["sni"] ?: q["peer"] ?: parsed.server
        val insecure = q["insecure"] == "1" || q["insecure"].equals("true", ignoreCase = true) ||
            q["allowinsecure"] == "1" || q["allowinsecure"].equals("true", ignoreCase = true)

        val map = linkedMapOf<String, Any>(
            "name" to parsed.remark,
            "type" to "hysteria2",
            "server" to parsed.server,
            "port" to parsed.port,
            "password" to parsed.userInfo,
            "sni" to sni
        )
        if (insecure) map["skip-cert-verify"] = true
        q["obfs"]?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }?.let { map["obfs"] = it }
        q["obfs-password"]?.takeIf { it.isNotEmpty() }?.let { map["obfs-password"] = it }
        q["up"]?.takeIf { it.isNotEmpty() }?.let { map["up"] = it }
            ?: q["upmbps"]?.takeIf { it.isNotEmpty() }?.let { map["up"] = "$it Mbps" }
        q["down"]?.takeIf { it.isNotEmpty() }?.let { map["down"] = it }
            ?: q["downmbps"]?.takeIf { it.isNotEmpty() }?.let { map["down"] = "$it Mbps" }
        q["pinsha256"]?.takeIf { it.isNotEmpty() }?.let { map["fingerprint"] = it }
        q["mport"]?.takeIf { it.isNotEmpty() }?.let { map["ports"] = it }
        q["hop-interval"]?.takeIf { it.isNotEmpty() }?.let { rawHop ->
            map["hop-interval"] = rawHop.toIntOrNull() ?: rawHop
        }
        q["alpn"]?.takeIf { it.isNotEmpty() }?.let { alpnRaw ->
            map["alpn"] = alpnRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return map
    }

    private fun parseTuic(uriString: String, index: Int): Map<String, Any>? {
        val parsed = parseStandardUri(uriString, "tuic://", "TUIC 节点 $index") ?: return null
        val q = parsed.queryParams
        val colonIdx = parsed.userInfo.indexOf(':')
        val uuid: String
        val password: String?
        if (colonIdx != -1) {
            uuid = parsed.userInfo.substring(0, colonIdx).trim()
            password = parsed.userInfo.substring(colonIdx + 1)
        } else {
            uuid = parsed.userInfo.trim()
            password = q["password"]
        }
        if (uuid.isEmpty()) return null

        val map = linkedMapOf<String, Any>(
            "name" to parsed.remark,
            "type" to "tuic",
            "server" to parsed.server,
            "port" to parsed.port,
            "uuid" to uuid,
            "udp" to true
        )
        password?.takeIf { it.isNotEmpty() }?.let { map["password"] = it }
        (q["sni"] ?: q["peer"])?.takeIf { it.isNotEmpty() }?.let { map["sni"] = it }
        q["alpn"]?.takeIf { it.isNotEmpty() }?.let { alpnRaw ->
            map["alpn"] = alpnRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        (q["congestion_control"] ?: q["congestion-controller"])?.takeIf { it.isNotEmpty() }?.let {
            map["congestion-controller"] = it
        }
        (q["udp_relay_mode"] ?: q["udp-relay-mode"])?.takeIf { it.isNotEmpty() }?.let {
            map["udp-relay-mode"] = it
        }
        if (isTruthyParam(q["allow_insecure"]) || isTruthyParam(q["allowinsecure"]) || isTruthyParam(q["insecure"])) {
            map["skip-cert-verify"] = true
        }
        if (isTruthyParam(q["disable_sni"]) || isTruthyParam(q["disable-sni"])) {
            map["disable-sni"] = true
        }
        if (isTruthyParam(q["reduce_rtt"]) || isTruthyParam(q["reduce-rtt"])) {
            map["reduce-rtt"] = true
        }
        return map
    }

    private fun parseWireguard(uriString: String, schemePrefix: String, index: Int): Map<String, Any>? {
        val parsed = parseStandardUri(uriString, schemePrefix, "WireGuard 节点 $index", defaultPort = 51820) ?: return null
        val q = parsed.queryParams
        val publicKey = (q["publickey"] ?: q["public-key"] ?: q["peer_public_key"])?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null

        val map = linkedMapOf<String, Any>(
            "name" to parsed.remark,
            "type" to "wireguard",
            "server" to parsed.server,
            "port" to parsed.port,
            "private-key" to parsed.userInfo,
            "public-key" to publicKey,
            "udp" to true
        )

        val addressEntries = (q["address"] ?: "")
            .split(",")
            .map { it.trim().substringBefore("/") }
            .filter { it.isNotEmpty() }
        val ipv4 = q["ip"]?.trim()?.substringBefore("/")?.takeIf { it.isNotEmpty() }
            ?: addressEntries.firstOrNull { !it.contains(':') }
        val ipv6 = q["ipv6"]?.trim()?.substringBefore("/")?.takeIf { it.isNotEmpty() }
            ?: addressEntries.firstOrNull { it.contains(':') }
        ipv4?.let { map["ip"] = it }
        ipv6?.let { map["ipv6"] = it }

        (q["presharedkey"] ?: q["preshared-key"] ?: q["psk"])?.takeIf { it.isNotEmpty() }?.let {
            map["preshared-key"] = it
        }
        q["mtu"]?.toIntOrNull()?.takeIf { it > 0 }?.let { map["mtu"] = it }
        q["reserved"]?.takeIf { it.isNotEmpty() }?.let { raw ->
            val bytes = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (bytes.isNotEmpty()) map["reserved"] = bytes
        }
        return map
    }

    private fun parseSocks5(uriString: String, schemePrefix: String, index: Int): Map<String, Any>? {
        val parts = splitUriParts(uriString, schemePrefix, "SOCKS5 节点 $index") ?: return null
        val q = parts.queryParams
        val authority = parts.authorityPart.trim()
        if (authority.isEmpty()) return null

        var username: String? = null
        var password: String? = null
        val hostPortRaw: String

        val atIdx = authority.indexOf('@')
        if (atIdx != -1) {
            val userPart = authority.substring(0, atIdx).trim()
            hostPortRaw = authority.substring(atIdx + 1).trim()
            val decodedUser = if (!userPart.contains(':')) {
                decodeBase64Utf8(userPart)?.takeIf { it.contains(':') } ?: safeUrlDecode(userPart)
            } else {
                safeUrlDecode(userPart)
            }
            val colonIdx = decodedUser.indexOf(':')
            if (colonIdx != -1) {
                username = decodedUser.substring(0, colonIdx)
                password = decodedUser.substring(colonIdx + 1)
            } else if (decodedUser.isNotEmpty()) {
                username = decodedUser
            }
        } else {
            val decodedFull = decodeBase64Utf8(authority)?.takeIf { it.contains('@') }
            if (decodedFull != null) {
                val innerAt = decodedFull.indexOf('@')
                val userPart = decodedFull.substring(0, innerAt)
                hostPortRaw = decodedFull.substring(innerAt + 1)
                val colonIdx = userPart.indexOf(':')
                if (colonIdx != -1) {
                    username = userPart.substring(0, colonIdx)
                    password = userPart.substring(colonIdx + 1)
                } else if (userPart.isNotEmpty()) {
                    username = userPart
                }
            } else {
                hostPortRaw = authority
            }
        }

        val (server, port) = parseHostPort(hostPortRaw, defaultPort = 1080) ?: return null
        val map = linkedMapOf<String, Any>(
            "name" to parts.remark,
            "type" to "socks5",
            "server" to server,
            "port" to port,
            "udp" to true
        )
        username?.takeIf { it.isNotEmpty() }?.let { map["username"] = it }
        password?.takeIf { it.isNotEmpty() }?.let { map["password"] = it }
        val useTls = isTruthyParam(q["tls"]) || q["security"].equals("tls", ignoreCase = true)
        if (useTls) {
            map["tls"] = true
            (q["sni"] ?: q["peer"])?.takeIf { it.isNotEmpty() }?.let { map["sni"] = it }
            if (isTruthyParam(q["allowinsecure"]) || isTruthyParam(q["insecure"])) {
                map["skip-cert-verify"] = true
            }
        }
        return map
    }

    private fun parseShadowsocks(uriString: String, index: Int): Map<String, Any>? {
        return try {
            val parts = splitUriParts(uriString, "ss://", "SS 节点 $index") ?: return null
            val mainPart = parts.authorityPart
            val queryParams = parts.queryParams
            val atIdx = mainPart.indexOf('@')

            val cipher: String
            val password: String
            val server: String
            val port: Int

            if (atIdx != -1) {
                val userPart = mainPart.substring(0, atIdx)
                val decodedUser = decodeBase64Utf8(userPart)?.takeIf { it.contains(':') }
                    ?: percentDecodeUtf8(userPart)
                val colon = decodedUser.indexOf(':')
                if (colon == -1) return null
                cipher = decodedUser.substring(0, colon)
                password = decodedUser.substring(colon + 1)
                val (s, p) = parseHostPort(mainPart.substring(atIdx + 1)) ?: return null
                server = s
                port = p
            } else {
                val decoded = decodeBase64Utf8(mainPart) ?: return null
                val at = decoded.indexOf('@')
                if (at == -1) return null
                val user = decoded.substring(0, at)
                val colon = user.indexOf(':')
                if (colon == -1) return null
                cipher = user.substring(0, colon)
                password = user.substring(colon + 1)
                val (s, p) = parseHostPort(decoded.substring(at + 1)) ?: return null
                server = s
                port = p
            }

            val map = linkedMapOf<String, Any>(
                "name" to parts.remark,
                "type" to "ss",
                "server" to server,
                "port" to port,
                "cipher" to cipher,
                "password" to password,
                "udp" to true
            )
            applyShadowsocksPlugin(map, queryParams["plugin"])
            map
        } catch (_: Exception) {
            null
        }
    }

    private fun applyShadowsocksPlugin(map: MutableMap<String, Any>, rawPlugin: String?) {
        val pluginSpec = rawPlugin?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val parts = pluginSpec.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return

        val rawName = parts.first().lowercase()
        val normalizedPlugin = when (rawName) {
            "obfs-local", "simple-obfs", "obfs" -> "obfs"
            else -> rawName
        }

        val pluginOpts = linkedMapOf<String, Any>()
        for (opt in parts.drop(1)) {
            val eqIdx = opt.indexOf('=')
            if (eqIdx == -1) {
                when (opt.lowercase()) {
                    "tls" -> pluginOpts["tls"] = true
                    "mux" -> pluginOpts["mux"] = true
                }
                continue
            }
            val key = opt.substring(0, eqIdx).trim().lowercase()
            val value = opt.substring(eqIdx + 1).trim()
            if (value.isEmpty()) continue

            when (normalizedPlugin) {
                "obfs" -> when (key) {
                    "obfs", "mode" -> pluginOpts["mode"] = value
                    "obfs-host", "host" -> pluginOpts["host"] = value
                    else -> pluginOpts[key] = value
                }
                "v2ray-plugin" -> when (key) {
                    "tls" -> pluginOpts["tls"] = value == "1" || value.equals("true", ignoreCase = true)
                    "skip-cert-verify", "allowinsecure" ->
                        pluginOpts["skip-cert-verify"] = value == "1" || value.equals("true", ignoreCase = true)
                    else -> pluginOpts[key] = value
                }
                else -> pluginOpts[key] = value
            }
        }

        map["plugin"] = normalizedPlugin
        if (pluginOpts.isNotEmpty()) {
            map["plugin-opts"] = pluginOpts
        }
    }

    private fun parseVmess(uriString: String, index: Int): Map<String, Any>? {
        return try {
            val withoutScheme = uriString.substring(8).trim()
            val jsonStr = decodeBase64Utf8(withoutScheme) ?: return null
            val json: JsonObject = Json.parseToJsonElement(jsonStr).jsonObject

            fun str(key: String, default: String = ""): String {
                val el: JsonElement = json[key] ?: return default
                return (el as? JsonPrimitive)?.content ?: default
            }

            fun intVal(key: String, default: Int): Int {
                val el = json[key] as? JsonPrimitive ?: return default
                return el.intOrNull ?: el.content.toIntOrNull() ?: default
            }

            val server = str("add")
            val uuid = str("id")
            if (server.isBlank() || uuid.isBlank()) return null

            val remarks = str("ps").ifBlank { "VMess 节点 $index" }
            val port = intVal("port", 443)
            if (port !in 1..65535) return null
            val alterId = intVal("aid", 0)
            val net = str("net", "tcp")
            val tls = str("tls")
            val host = str("host")
            val sni = str("sni").ifBlank { host }
            val path = str("path")
            val scy = str("scy").ifBlank { "auto" }

            val map = linkedMapOf<String, Any>(
                "name" to remarks,
                "type" to "vmess",
                "server" to server,
                "port" to port,
                "uuid" to uuid,
                "alterId" to alterId,
                "cipher" to scy,
                "udp" to true
            )
            if (net.isNotEmpty()) map["network"] = net
            if (tls.equals("tls", ignoreCase = true)) {
                map["tls"] = true
                if (sni.isNotEmpty()) map["servername"] = sni
            }
            if (net.equals("ws", ignoreCase = true)) {
                val wsMap = linkedMapOf<String, Any>()
                if (path.isNotEmpty()) wsMap["path"] = path
                if (host.isNotEmpty()) wsMap["headers"] = linkedMapOf("Host" to host)
                map["ws-opts"] = wsMap
            } else if (net.equals("grpc", ignoreCase = true) && path.isNotEmpty()) {
                map["grpc-opts"] = linkedMapOf("grpc-service-name" to path)
            }
            map
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHostPort(hostPort: String, defaultPort: Int = 443): Pair<String, Int>? {
        if (hostPort.isBlank()) return null
        val cleaned = hostPort.substringBefore("/").substringBefore("?").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.startsWith("[")) {
            val closeBracket = cleaned.indexOf(']')
            if (closeBracket <= 1) return null
            val host = cleaned.substring(1, closeBracket).trim()
            if (host.isEmpty()) return null
            val remainder = cleaned.substring(closeBracket + 1)
            val port = when {
                remainder.isEmpty() -> defaultPort
                remainder.startsWith(":") -> {
                    val portStr = remainder.substring(1)
                    if (portStr.isEmpty()) return null
                    portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                }
                else -> return null
            }
            Pair(host, port)
        } else {
            val colonIdx = cleaned.lastIndexOf(':')
            if (colonIdx != -1) {
                if (cleaned.indexOf(':') != colonIdx) return null
                val host = cleaned.substring(0, colonIdx).trim()
                val portStr = cleaned.substring(colonIdx + 1).trim()
                if (host.isEmpty() || portStr.isEmpty()) return null
                val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                Pair(host, port)
            } else {
                Pair(cleaned, defaultPort)
            }
        }
    }

    private fun decodeBase64Utf8(input: String): String? {
        val compact = input.trim().filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        val standard = compact.replace('-', '+').replace('_', '/')
        val padded = when (standard.length % 4) {
            2 -> "$standard=="
            3 -> "$standard="
            else -> standard
        }
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun safeUrlDecode(s: String): String = percentDecodeUtf8(s)

    /**
     * Decodes RFC-3986 `%XX` UTF-8 percent-encoded sequences without turning literal `+` into spaces,
     * which protects passwords, remarks, and Base64 credentials in proxy URIs.
     */
    fun percentDecodeUtf8(input: String): String {
        if (!input.contains('%')) return input
        val out = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '%' && i + 2 < input.length) {
                val hi = Character.digit(input[i + 1], 16)
                val lo = Character.digit(input[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes)
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
