package com.clashflux.android.ui.profiles

/**
 * Pure mapping and deep-copy helpers for the manual node editor form.
 */
internal data class ProxyFormInput(
    val name: String,
    val type: String,
    val server: String,
    val port: String,
    val credential: String,
    val secondarySecret: String = "",
    val username: String = "",
    val cipher: String = "",
    val alterId: String = "0",
    val network: String = "tcp",
    val wsPath: String = "",
    val wsHost: String = "",
    val grpcServiceName: String = "",
    val sni: String = "",
    val tlsEnabled: Boolean = false,
    val skipCertVerify: Boolean = false,
    val udpEnabled: Boolean = true
)

internal fun defaultPortForType(type: String): Int = when (type.lowercase()) {
    "ss" -> 8388
    "socks5" -> 1080
    "http" -> 8080
    else -> 443
}

/**
 * Validates [input] and returns a clean deep-copied Clash proxy map ready for YAML persistence.
 */
internal fun buildEditedProxyMap(
    input: ProxyFormInput,
    initialProxy: Map<String, Any>?
): Result<MutableMap<String, Any>> {
    val cleanServer = input.server.trim()
    val parsedPort = input.port.trim().toIntOrNull()
    val cleanCredential = input.credential.trim()
    val cleanSecondarySecret = input.secondarySecret.trim()
    val cleanUsername = input.username.trim()
    val cleanCipher = input.cipher.trim()
    val normalizedType = input.type.trim().lowercase()

    if (cleanServer.isEmpty()) {
        return Result.failure(IllegalArgumentException("请输入服务器地址"))
    }
    if (parsedPort == null || parsedPort !in 1..65535) {
        return Result.failure(IllegalArgumentException("请输入 1 到 65535 之间的有效端口"))
    }

    when (normalizedType) {
        "vless", "vmess", "tuic" -> if (cleanCredential.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入节点的 UUID"))
        }
        "ss" -> {
            if (cleanCredential.isEmpty()) {
                return Result.failure(IllegalArgumentException("请输入节点的密码"))
            }
            if (cleanCipher.isEmpty()) {
                return Result.failure(IllegalArgumentException("请输入 Shadowsocks 加密方式"))
            }
        }
        "trojan", "hysteria2" -> if (cleanCredential.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入节点的密码"))
        }
    }

    val parsedAlterId = if (normalizedType == "vmess") {
        val rawAid = input.alterId.trim().ifEmpty { "0" }.toIntOrNull()
        if (rawAid == null || rawAid < 0) {
            return Result.failure(IllegalArgumentException("AlterId 必须为非负整数"))
        }
        rawAid
    } else {
        0
    }

    val result = (initialProxy?.let(::deepCopyProxyMap) ?: linkedMapOf()).apply {
        put("name", input.name.trim().ifEmpty { "自定义节点" })
        put("type", normalizedType)
        put("server", cleanServer)
        put("port", parsedPort)
        put("udp", input.udpEnabled)

        when (normalizedType) {
            "ss" -> {
                remove("uuid")
                remove("username")
                remove("alterId")
                put("cipher", cleanCipher)
                put("password", cleanCredential)
            }
            "trojan", "hysteria2" -> {
                remove("uuid")
                remove("username")
                remove("alterId")
                put("password", cleanCredential)
            }
            "tuic" -> {
                remove("username")
                remove("alterId")
                put("uuid", cleanCredential)
                if (cleanSecondarySecret.isNotEmpty()) put("password", cleanSecondarySecret) else remove("password")
            }
            "vmess" -> {
                remove("password")
                remove("username")
                put("uuid", cleanCredential)
                put("alterId", parsedAlterId)
                put("cipher", cleanCipher.ifEmpty { "auto" })
            }
            "vless" -> {
                remove("password")
                remove("username")
                remove("alterId")
                put("uuid", cleanCredential)
            }
            "socks5", "http" -> {
                remove("uuid")
                remove("alterId")
                if (cleanUsername.isNotEmpty()) put("username", cleanUsername) else remove("username")
                if (cleanCredential.isNotEmpty()) put("password", cleanCredential) else remove("password")
            }
        }

        if (normalizedType in setOf("vless", "vmess", "trojan")) {
            val net = input.network.trim().lowercase().ifEmpty { "tcp" }
            put("network", net)
            when (net) {
                "ws" -> {
                    remove("grpc-opts")
                    val wsMap = get("ws-opts").toMutableStringAnyMap()
                    val cleanPath = input.wsPath.trim()
                    if (cleanPath.isNotEmpty()) wsMap["path"] = cleanPath else wsMap.remove("path")

                    val headers = wsMap["headers"].toMutableStringAnyMap()
                    headers.remove("host")
                    val cleanHost = input.wsHost.trim()
                    if (cleanHost.isNotEmpty()) headers["Host"] = cleanHost else headers.remove("Host")
                    if (headers.isNotEmpty()) wsMap["headers"] = headers else wsMap.remove("headers")
                    put("ws-opts", wsMap)
                }
                "grpc" -> {
                    remove("ws-opts")
                    val grpcMap = get("grpc-opts").toMutableStringAnyMap()
                    val cleanSvc = input.grpcServiceName.trim()
                    if (cleanSvc.isNotEmpty()) {
                        grpcMap["grpc-service-name"] = cleanSvc
                    } else {
                        grpcMap.remove("grpc-service-name")
                    }
                    if (grpcMap.isNotEmpty()) put("grpc-opts", grpcMap) else remove("grpc-opts")
                }
                else -> {
                    remove("ws-opts")
                    remove("grpc-opts")
                }
            }
        }

        if (input.tlsEnabled) {
            put("tls", true)
        } else {
            remove("tls")
        }

        if (input.skipCertVerify) {
            put("skip-cert-verify", true)
        } else {
            remove("skip-cert-verify")
        }

        val cleanSni = input.sni.trim()
        if (cleanSni.isNotEmpty()) {
            if (normalizedType == "vless" || normalizedType == "vmess") {
                remove("sni")
                put("servername", cleanSni)
            } else {
                remove("servername")
                put("sni", cleanSni)
            }
        } else {
            remove("sni")
            remove("servername")
        }
    }
    return Result.success(result)
}

private fun Any?.toMutableStringAnyMap(): MutableMap<String, Any> =
    (this as? Map<*, *>)
        ?.entries
        ?.filter { it.key != null && it.value != null }
        ?.associateTo(linkedMapOf()) { it.key.toString() to it.value!! }
        ?: linkedMapOf()

private fun deepCopyValue(value: Any): Any = when (value) {
    is Map<*, *> -> value.entries
        .filter { it.key != null && it.value != null }
        .associateTo(linkedMapOf()) { it.key.toString() to deepCopyValue(it.value!!) }
    is List<*> -> value.filterNotNull().map(::deepCopyValue).toMutableList()
    else -> value
}

internal fun deepCopyProxyMap(source: Map<String, Any>): MutableMap<String, Any> {
    val result = linkedMapOf<String, Any>()
    source.forEach { (k, v) -> result[k] = deepCopyValue(v) }
    return result
}
