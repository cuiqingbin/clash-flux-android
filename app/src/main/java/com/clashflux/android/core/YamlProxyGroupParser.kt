package com.clashflux.android.core

import com.clashflux.android.model.ProxyGroup
import com.clashflux.android.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.yaml.snakeyaml.Yaml

/**
 * Pure-Kotlin helper that parses Clash YAML proxy groups and merges live REST / Bridge snapshots.
 */
object YamlProxyGroupParser {

    data class ParsedProxyConfig(
        val groups: List<ProxyGroup>,
        val hostPortMap: Map<String, Pair<String, Int>>
    )

    data class BridgeGroupSnapshot(
        val type: String,
        val now: String,
        val nodes: List<ProxyNode>,
        val delays: Map<String, Int>
    )

    /**
     * Picks the primary user-facing selector group consistently across UI display and delay warmup.
     */
    fun selectPrimaryGroup(groups: List<ProxyGroup>): ProxyGroup? {
        return groups.find { it.name.contains("节点") }
            ?: groups.find { it.type.equals("select", ignoreCase = true) }
            ?: groups.firstOrNull()
    }

    fun parse(
        rawYaml: String,
        readSavedSelector: (groupName: String) -> String? = { null }
    ): ParsedProxyConfig {
        val text = ClashConfigEditor.sanitizeYaml(rawYaml)
        if (text.isBlank()) return ParsedProxyConfig(emptyList(), emptyMap())

        val data = runCatching { Yaml().load<Map<String, Any>>(text) }.getOrNull()
        val nodeTypeMap = mutableMapOf<String, String>()
        val hostPortMap = mutableMapOf<String, Pair<String, Int>>()

        (data?.get("proxies") as? List<*>)?.forEach { p ->
            val pMap = p as? Map<*, *> ?: return@forEach
            val pName = pMap["name"]?.toString() ?: return@forEach
            val pType = pMap["type"]?.toString() ?: "vless"
            nodeTypeMap[pName] = pType
            val server = pMap["server"]?.toString() ?: return@forEach
            val port = pMap["port"]?.toString()?.toIntOrNull()
                ?: (pMap["port"] as? Number)?.toInt()
                ?: return@forEach
            hostPortMap[pName] = server to port
        }

        if (nodeTypeMap.isEmpty()) {
            extractProxyFallbackFromText(text, nodeTypeMap, hostPortMap)
        }

        val parsedGroups = mutableListOf<ProxyGroup>()
        val groupTypeMap = mutableMapOf<String, String>()
        val rawGroups = data?.get("proxy-groups") as? List<*>

        if (!rawGroups.isNullOrEmpty()) {
            rawGroups.forEach { item ->
                val groupMap = item as? Map<*, *> ?: return@forEach
                val gName = groupMap["name"]?.toString() ?: return@forEach
                groupTypeMap[gName] = groupMap["type"]?.toString() ?: "select"
            }
            rawGroups.forEach { item ->
                val groupMap = item as? Map<*, *> ?: return@forEach
                val name = groupMap["name"]?.toString() ?: return@forEach
                val type = groupMap["type"]?.toString() ?: "select"
                val proxiesList = (groupMap["proxies"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                val savedNow = readSavedSelector(name)
                val preferredNow = if (!savedNow.isNullOrBlank() && proxiesList.contains(savedNow)) {
                    savedNow
                } else {
                    proxiesList.firstOrNull { nodeTypeMap.containsKey(it) }
                        ?: proxiesList.firstOrNull()
                        ?: ""
                }
                parsedGroups.add(
                    ProxyGroup(
                        name = name,
                        type = type,
                        now = preferredNow,
                        all = proxiesList.map { nodeName ->
                            val resolvedType = when {
                                nodeTypeMap.containsKey(nodeName) -> nodeTypeMap[nodeName]!!
                                groupTypeMap.containsKey(nodeName) -> groupTypeMap[nodeName]!!
                                nodeName.equals("DIRECT", ignoreCase = true) -> "DIRECT"
                                nodeName.equals("REJECT", ignoreCase = true) -> "REJECT"
                                else -> type
                            }
                            ProxyNode(name = nodeName, type = resolvedType)
                        }
                    )
                )
            }
        }

        if (parsedGroups.isEmpty() && nodeTypeMap.isNotEmpty()) {
            val proxyNames = nodeTypeMap.keys.toList()
            parsedGroups.add(
                ProxyGroup(
                    name = "🚀 节点选择",
                    type = "select",
                    now = proxyNames.first(),
                    all = proxyNames.map { ProxyNode(name = it, type = nodeTypeMap[it] ?: "vless") } +
                        ProxyNode(name = "DIRECT", type = "DIRECT")
                )
            )
        }

        return ParsedProxyConfig(groups = parsedGroups, hostPortMap = hostPortMap)
    }

    fun mergeLiveProxies(
        currentGroups: List<ProxyGroup>,
        liveProxies: Map<String, MihomoControllerClient.LiveProxyInfo>
    ): List<ProxyGroup> {
        if (liveProxies.isEmpty()) return currentGroups

        val baseGroups = if (currentGroups.isNotEmpty()) {
            currentGroups
        } else {
            val discovered = liveProxies.values.filter {
                it.all.isNotEmpty() && !it.name.equals("GLOBAL", ignoreCase = true)
            }
            val candidates = discovered.ifEmpty {
                liveProxies.values.filter { it.all.isNotEmpty() }
            }
            candidates.map { info ->
                ProxyGroup(
                    name = info.name,
                    type = info.type.ifBlank { "select" },
                    now = info.now,
                    all = emptyList()
                )
            }
        }

        return baseGroups.map { group ->
            val live = liveProxies[group.name] ?: return@map group
            val existingByName = group.all.associateBy { it.name }
            val candidateNames = if (live.all.isNotEmpty()) live.all else group.all.map { it.name }
            val nextType = live.type.ifBlank { group.type }
            val nextNodes = candidateNames.map { nodeName ->
                val existing = existingByName[nodeName]
                val liveNode = liveProxies[nodeName]
                ProxyNode(
                    name = nodeName,
                    type = liveNode?.type?.takeIf { it.isNotBlank() } ?: existing?.type ?: nextType,
                    delay = liveNode?.delay?.takeIf { it > 0 } ?: existing?.delay,
                    isTesting = existing?.isTesting ?: false
                )
            }
            group.copy(
                type = nextType,
                now = live.now.ifBlank { group.now },
                all = nextNodes
            )
        }
    }

    fun parseBridgeGroupSnapshot(groupInfoJson: String): BridgeGroupSnapshot? {
        if (groupInfoJson.isBlank()) return null
        return try {
            val groupJson = Json.parseToJsonElement(groupInfoJson).jsonObject
            val groupType = (groupJson["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "select"
            val now = (groupJson["now"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val delays = linkedMapOf<String, Int>()
            val nodesByName = linkedMapOf<String, ProxyNode>()

            (groupJson["proxies"] as? JsonArray)?.forEach { elem ->
                val item = elem as? JsonObject ?: return@forEach
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (name.isEmpty()) return@forEach
                val d = (item["delay"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65534 }
                if (d != null) delays[name] = d
                val itemType = (item["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: groupType
                nodesByName[name] = ProxyNode(name = name, type = itemType, delay = d)
            }

            (groupJson["all"] as? JsonArray)?.forEach { elem ->
                val item = elem as? JsonObject
                if (item == null) {
                    val rawName = (elem as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (rawName.isNotEmpty() && !nodesByName.containsKey(rawName)) {
                        nodesByName[rawName] = ProxyNode(name = rawName, type = groupType)
                    }
                    return@forEach
                }
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (name.isEmpty()) return@forEach
                val history = item["history"] as? JsonArray
                val lastHistory = history?.lastOrNull() as? JsonObject
                val histDelay = (lastHistory?.get("delay") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65534 }
                if (histDelay != null) delays[name] = histDelay
                val prev = nodesByName[name]
                val itemType = (item["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: prev?.type
                    ?: groupType
                nodesByName[name] = ProxyNode(
                    name = name,
                    type = itemType,
                    delay = histDelay ?: prev?.delay
                )
            }

            BridgeGroupSnapshot(
                type = groupType,
                now = now,
                nodes = nodesByName.values.toList(),
                delays = delays
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun mergeBridgeSnapshots(
        currentGroups: List<ProxyGroup>,
        groupNames: List<String>,
        snapshots: Map<String, BridgeGroupSnapshot?>
    ): List<ProxyGroup> {
        val baseGroups = currentGroups.ifEmpty {
            groupNames.mapNotNull { name ->
                val snap = snapshots[name] ?: return@mapNotNull null
                ProxyGroup(name = name, type = snap.type, now = snap.now, all = snap.nodes)
            }
        }
        return baseGroups.map { group ->
            val snap = snapshots[group.name] ?: return@map group
            val existingByName = group.all.associateBy { it.name }
            val nextNodes = if (snap.nodes.isNotEmpty()) {
                snap.nodes.map { n ->
                    val existing = existingByName[n.name]
                    n.copy(
                        delay = n.delay ?: existing?.delay,
                        isTesting = existing?.isTesting ?: false
                    )
                }
            } else {
                group.all
            }
            group.copy(
                type = snap.type.ifBlank { group.type },
                now = snap.now.ifBlank { group.now },
                all = nextNodes
            )
        }
    }

    fun parseGroupNamesJson(rawJson: String): List<String> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(rawJson).jsonArray.mapNotNull { elem ->
                (elem as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractProxyFallbackFromText(
        text: String,
        nodeTypeMap: MutableMap<String, String>,
        hostPortMap: MutableMap<String, Pair<String, Int>>
    ) {
        var inProxiesSection = false
        var currentName: String? = null
        var currentType: String? = null
        var currentServer: String? = null
        var currentPort: Int? = null

        fun flush() {
            val n = currentName
            if (!n.isNullOrEmpty()) {
                nodeTypeMap[n] = currentType ?: "vless"
                val s = currentServer
                val p = currentPort
                if (!s.isNullOrEmpty() && p != null) {
                    hostPortMap[n] = s to p
                }
            }
            currentName = null
            currentType = null
            currentServer = null
            currentPort = null
        }

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("proxies:")) {
                inProxiesSection = true
                continue
            }
            if (inProxiesSection) {
                if (!line.startsWith(" ") && !line.startsWith("\t") && line.contains(":") && !trimmed.startsWith("-")) {
                    flush()
                    inProxiesSection = false
                    continue
                }
                if (trimmed.startsWith("- name:") || (trimmed.startsWith("-") && trimmed.contains("name:"))) {
                    flush()
                    currentName = trimmed.substringAfter("name:").substringBefore(",").substringBefore("}").trim().trim('"', '\'')
                } else if (trimmed.startsWith("type:")) {
                    currentType = trimmed.substringAfter("type:").trim().trim('"', '\'')
                } else if (trimmed.startsWith("server:")) {
                    currentServer = trimmed.substringAfter("server:").trim().trim('"', '\'')
                } else if (trimmed.startsWith("port:")) {
                    currentPort = trimmed.substringAfter("port:").trim().trim('"', '\'').toIntOrNull()
                }
            }
        }
        flush()
    }
}
