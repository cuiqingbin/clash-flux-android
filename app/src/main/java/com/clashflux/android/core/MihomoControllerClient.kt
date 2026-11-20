package com.clashflux.android.core

import com.clashflux.android.data.AppSettings
import com.clashflux.android.model.ConnectionItem
import com.clashflux.android.model.ConnectionMetadata
import com.clashflux.android.model.ProxyMode
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for Mihomo's embedded external controller (`127.0.0.1:9090`) protected by a
 * per-session random bearer token injected into Mihomo's session override slot.
 */
object MihomoControllerClient {

    data class LiveProxyInfo(
        val name: String,
        val type: String,
        val now: String = "",
        val all: List<String> = emptyList(),
        val delay: Int = -1
    )

    const val CONTROLLER_HOST = "127.0.0.1"
    const val CONTROLLER_PORT = 9090
    const val CONTROLLER_ADDRESS = "$CONTROLLER_HOST:$CONTROLLER_PORT"
    const val BASE_URL = "http://$CONTROLLER_ADDRESS"
    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    val sessionSecret: String = UUID.randomUUID().toString().replace("-", "")

    private val client: OkHttpClient = NetworkClients.baseClient.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Authorization", "Bearer $sessionSecret")
                .build()
            chain.proceed(req)
        }
        .build()

    private val geoClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun buildOverrideJson(settings: AppSettings): String {
        return buildJsonObject {
            put("external-controller", CONTROLLER_ADDRESS)
            put("secret", sessionSecret)
            put("allow-lan", settings.allowLan)
            put("ipv6", settings.ipv6Enabled)
            put("log-level", settings.logLevel)
            put("mode", settings.proxyMode.coreValue)
            put("mixed-port", settings.mixedPort)
            put("port", settings.httpPort)
            put("socks-port", settings.socksPort)
            put("find-process-mode", "strict")
            putJsonObject("dns") {
                put("ipv6", settings.ipv6Enabled)
            }
        }.toString()
    }

    suspend fun patchConfig(
        mode: ProxyMode? = null,
        allowLan: Boolean? = null,
        ipv6: Boolean? = null,
        logLevel: String? = null,
        mixedPort: Int? = null,
        port: Int? = null,
        socksPort: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            mode?.let { put("mode", it.coreValue) }
            allowLan?.let { put("allow-lan", it) }
            ipv6?.let { put("ipv6", it) }
            logLevel?.let { put("log-level", it) }
            mixedPort?.let { put("mixed-port", it) }
            port?.let { put("port", it) }
            socksPort?.let { put("socks-port", it) }
        }
        if (payload.isEmpty()) return@withContext true

        val request = Request.Builder()
            .url("$BASE_URL/configs")
            .patch(payload.toString().toRequestBody(jsonMediaType))
            .build()

        executeSuccess(request)
    }

    suspend fun updateGeoDatabase(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/configs/geo")
            .post("{}".toRequestBody(jsonMediaType))
            .build()

        try {
            geoClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getProxies(): Map<String, LiveProxyInfo>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/proxies")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parseProxiesJson(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun selectProxy(groupName: String, proxyName: String): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("name", proxyName) }.toString()
        val request = Request.Builder()
            .url("$BASE_URL/proxies/${encodePathSegment(groupName)}")
            .put(body.toRequestBody(jsonMediaType))
            .build()

        executeSuccess(request)
    }

    suspend fun testProxyDelay(
        proxyName: String,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Int = 5000
    ): Int? = withContext(Dispatchers.IO) {
        val encodedName = encodePathSegment(proxyName)
        val encodedUrl = URLEncoder.encode(testUrl, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/proxies/$encodedName/delay?timeout=$timeoutMs&url=$encodedUrl")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val obj = json.parseToJsonElement(body).jsonObject
                val delay = obj["delay"]?.jsonPrimitive?.intOrNull ?: return@withContext null
                delay.takeIf { it in 1..65534 }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun testGroupDelay(
        groupName: String,
        testUrl: String = DEFAULT_TEST_URL,
        timeoutMs: Int = 5000
    ): Map<String, Int>? = withContext(Dispatchers.IO) {
        val encodedGroup = encodePathSegment(groupName)
        val encodedUrl = URLEncoder.encode(testUrl, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/group/$encodedGroup/delay?timeout=$timeoutMs&url=$encodedUrl")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val obj = json.parseToJsonElement(body).jsonObject
                buildMap {
                    obj.forEach { (nodeName, value) ->
                        val delay = (value as? JsonPrimitive)?.intOrNull
                        if (delay != null && delay in 1..65534) {
                            put(nodeName, delay)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getConnections(): List<ConnectionItem>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/connections")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parseConnectionsJson(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun closeConnection(connectionId: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/connections/${encodePathSegment(connectionId)}")
            .delete()
            .build()

        executeSuccess(request)
    }

    suspend fun closeAllConnections(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/connections")
            .delete()
            .build()

        executeSuccess(request)
    }

    private fun executeSuccess(request: Request): Boolean {
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    internal fun parseConnectionsJson(jsonStr: String): List<ConnectionItem> {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val array = root["connections"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id").ifEmpty { return@mapNotNull null }
            val metaObj = obj["metadata"] as? JsonObject
            val metadata = ConnectionMetadata(
                network = metaObj?.str("network")?.ifEmpty { "tcp" } ?: "tcp",
                type = metaObj?.str("type")?.ifEmpty { "Unknown" } ?: "Unknown",
                sourceIP = metaObj?.str("sourceIP").orEmpty(),
                sourcePort = metaObj?.str("sourcePort").orEmpty(),
                host = metaObj?.str("host").orEmpty(),
                destinationIP = metaObj?.str("destinationIP")?.ifEmpty { metaObj.str("remoteDestination") }.orEmpty(),
                destinationPort = metaObj?.str("destinationPort").orEmpty(),
                process = metaObj?.str("process").orEmpty(),
                processPath = metaObj?.str("processPath").orEmpty(),
                uid = metaObj?.get("uid")?.jsonPrimitive?.intOrNull ?: 0
            )
            val chains = (obj["chains"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()

            ConnectionItem(
                id = id,
                metadata = metadata,
                upload = obj["upload"]?.jsonPrimitive?.longOrNull ?: 0L,
                download = obj["download"]?.jsonPrimitive?.longOrNull ?: 0L,
                start = formatStartTime(obj.str("start")),
                rule = obj.str("rule"),
                rulePayload = obj.str("rulePayload"),
                chains = chains
            )
        }
    }

    internal fun parseProxiesJson(jsonStr: String): Map<String, LiveProxyInfo> {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val proxiesObj = root["proxies"] as? JsonObject ?: return emptyMap()
        return buildMap {
            proxiesObj.forEach { (name, elem) ->
                val obj = elem as? JsonObject ?: return@forEach
                val type = obj.str("type").ifEmpty { "Unknown" }
                val now = obj.str("now")
                val all = (obj["all"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: emptyList()
                val latestDelay = (obj["history"] as? JsonArray)
                    ?.lastOrNull()
                    ?.let { (it as? JsonObject)?.get("delay")?.jsonPrimitive?.intOrNull }
                    ?.takeIf { it in 1..65534 }
                    ?: -1
                put(
                    name,
                    LiveProxyInfo(
                        name = name,
                        type = type,
                        now = now,
                        all = all,
                        delay = latestDelay
                    )
                )
            }
        }
    }

    private fun JsonObject.str(key: String): String {
        return (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
    }

    private fun formatStartTime(raw: String): String {
        if (raw.isBlank()) return ""
        val tIdx = raw.indexOf('T')
        return if (tIdx != -1 && raw.length >= tIdx + 9) {
            raw.substring(tIdx + 1, tIdx + 9)
        } else {
            raw.take(19)
        }
    }

    private fun encodePathSegment(segment: String): String {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
}
