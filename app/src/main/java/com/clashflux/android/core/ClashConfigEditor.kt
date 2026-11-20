package com.clashflux.android.core

import com.clashflux.android.model.ClashRule
import java.time.Instant
import java.util.Date
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

object ClashConfigEditor {

    private val legacyRedundantGoogleSuffixes = setOf(
        "googleapis.cn", "googleapis.com", "xn--ngstr-lra8j.com",
        "gvt1.com", "gvt2.com", "gvt3.com", "ggpht.com",
        "gstatic.com", "gstatic.cn", "android.com",
        "google.com", "googlezip.net", "1e100.net"
    )

    private val builtInPolicies = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")

    fun sanitizeYaml(yamlContent: String): String {
        return yamlContent.filter { ch -> ch >= ' ' || ch == '\n' || ch == '\r' || ch == '\t' }
    }

    /**
     * Validates that [yamlContent] parses into a non-empty YAML mapping.
     * Returns `null` when valid, or a descriptive error message otherwise.
     */
    fun validateYamlSyntax(yamlContent: String): String? {
        val sanitized = sanitizeYaml(yamlContent).trim()
        if (sanitized.isEmpty()) return "配置内容不能为空"
        return try {
            val loaded = Yaml().load<Any>(sanitized)
            if (loaded is Map<*, *>) null else "根节点必须为 YAML 键值映射"
        } catch (e: Exception) {
            "YAML 语法错误: ${e.message ?: "格式无效"}"
        }
    }

    /**
     * Preserves user-defined `# custom` rules ahead of refreshed upstream rules without duplicating identical entries.
     */
    fun mergeCustomRules(newYaml: String, customRules: List<ClashRule>): String {
        if (customRules.isEmpty()) return newYaml
        val sanitized = sanitizeYaml(newYaml)
        val incoming = parseRules(sanitized)
        val incomingKeys = incoming.map { "${it.type.uppercase()}|${it.payload.trim()}|${it.policy}" }.toSet()
        val dedupedCustom = customRules.filter { rule ->
            "${rule.type.uppercase()}|${rule.payload.trim()}|${rule.policy}" !in incomingKeys
        }
        return if (dedupedCustom.isEmpty()) sanitized else saveRules(sanitized, dedupedCustom + incoming)
    }

    /**
     * Serializes a top-level YAML key/value mapping (such as `proxies:` or `proxy-groups:`)
     * using SnakeYAML so nested maps (`ws-opts`, `reality-opts`, `grpc-opts`) and special
     * characters in strings are escaped safely.
     */
    fun dumpYamlSection(sectionKey: String, value: Any): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 4
            indicatorIndent = 2
            isPrettyFlow = true
            isAllowUnicode = true
            splitLines = false
            width = 4096
        }
        val yaml = Yaml(options)
        return yaml.dump(linkedMapOf(sectionKey to value)).trimEnd()
    }

    /**
     * Removes any desktop-style `tun:` block that conflicts with Android's `VpnService` TUN fd,
     * and ensures missing port/DNS defaults are populated for minimal subscription YAMLs.
     */
    fun ensureMobileRuntimeDefaults(
        yamlContent: String,
        mixedPort: Int = 7892,
        httpPort: Int = 7890,
        socksPort: Int = 7891
    ): String {
        val sanitized = sanitizeYaml(yamlContent)
        if (sanitized.isBlank()) return sanitized

        val withoutDesktopTun = replaceTopLevelSection(sanitized, "tun", "").trim()
        val topLevelKeys = collectTopLevelKeys(withoutDesktopTun)

        val missingHeader = buildString {
            if ("port" !in topLevelKeys) appendLine("port: $httpPort")
            if ("socks-port" !in topLevelKeys) appendLine("socks-port: $socksPort")
            if ("mixed-port" !in topLevelKeys) appendLine("mixed-port: $mixedPort")
            if ("dns" !in topLevelKeys) {
                appendLine("dns:")
                appendLine("  enable: true")
                appendLine("  ipv6: false")
                appendLine("  enhanced-mode: fake-ip")
                appendLine("  fake-ip-range: 198.18.0.1/16")
                appendLine("  default-nameserver: [223.5.5.5, 119.29.29.29]")
                appendLine("  nameserver:")
                appendLine("    - https://dns.alidns.com/dns-query")
                appendLine("    - https://doh.pub/dns-query")
            }
        }.trimEnd()

        return if (missingHeader.isEmpty()) {
            withoutDesktopTun
        } else if (withoutDesktopTun.isEmpty()) {
            missingHeader
        } else {
            "$missingHeader\n$withoutDesktopTun"
        }
    }

    private fun collectTopLevelKeys(yamlContent: String): Set<String> {
        val keys = hashSetOf<String>()
        for (line in yamlContent.lines()) {
            val trimmed = line.trim()
            val isTopLevelKey = line.isNotEmpty() &&
                !line.startsWith(" ") &&
                !line.startsWith("\t") &&
                !trimmed.startsWith("#") &&
                !trimmed.startsWith("-") &&
                trimmed.contains(":")
            if (isTopLevelKey) {
                keys.add(trimmed.substringBefore(":").trim())
            }
        }
        return keys
    }

    /**
     * Replaces a top-level YAML section (e.g. `rules`, `proxies`, `proxy-groups`) while
     * preserving comments and formatting in unrelated sections.
     */
    private fun replaceTopLevelSection(
        yamlContent: String,
        sectionKey: String,
        renderedSection: String
    ): String {
        val sanitized = sanitizeYaml(yamlContent)
        val lines = sanitized.lines()
        val result = mutableListOf<String>()
        var inTargetSection = false
        var sectionWritten = false

        for (line in lines) {
            val trimmed = line.trim()
            val isTopLevelKey = line.isNotEmpty() &&
                !line.startsWith(" ") &&
                !line.startsWith("\t") &&
                !trimmed.startsWith("#") &&
                !trimmed.startsWith("-") &&
                trimmed.contains(":")

            if (isTopLevelKey && trimmed.substringBefore(":").trim() == sectionKey) {
                inTargetSection = true
                if (renderedSection.isNotBlank()) {
                    result.addAll(renderedSection.lines())
                }
                sectionWritten = true
                continue
            }

            if (inTargetSection) {
                if (isTopLevelKey) {
                    inTargetSection = false
                    result.add(line)
                }
                continue
            }

            result.add(line)
        }

        if (!sectionWritten && renderedSection.isNotBlank()) {
            if (result.isNotEmpty() && result.last().isNotBlank()) {
                result.add("")
            }
            result.addAll(renderedSection.lines())
        }

        return result.joinToString("\n")
    }

    /**
     * Returns normalized rules YAML without mutating any caller-owned files on disk.
     * Collapses legacy redundant Google domain suffixes only when `GEOSITE,google` is already
     * present so whitelist/custom configurations without `GEOSITE,google` remain untouched.
     */
    fun normalizeSubscriptionRules(rawYaml: String): String {
        val sanitized = sanitizeYaml(rawYaml)
        val rules = parseRules(sanitized)
        if (rules.isEmpty()) return sanitized

        val hasGoogleGeosite = rules.any {
            it.type.equals("GEOSITE", ignoreCase = true) && it.payload.equals("google", ignoreCase = true)
        }
        if (!hasGoogleGeosite) return sanitized

        val cleaned = rules.filterNot { rule ->
            !rule.isCustom &&
                rule.type.equals("DOMAIN-SUFFIX", ignoreCase = true) &&
                rule.payload.lowercase() in legacyRedundantGoogleSuffixes
        }

        return if (cleaned != rules) saveRules(sanitized, cleaned) else sanitized
    }

    fun parseRules(yamlContent: String): List<ClashRule> {
        val rules = mutableListOf<ClashRule>()
        var inRulesSection = false

        for (line in sanitizeYaml(yamlContent).lines()) {
            val trimmed = line.trim()
            val isTopLevelKey = line.isNotEmpty() &&
                !line.startsWith(" ") &&
                !line.startsWith("\t") &&
                !trimmed.startsWith("#") &&
                !trimmed.startsWith("-") &&
                trimmed.contains(":")

            if (isTopLevelKey && trimmed.substringBefore(":").trim() == "rules") {
                inRulesSection = true
                val inlineRest = trimmed.substringAfter(":").trim()
                if (inlineRest.startsWith("[") && inlineRest.endsWith("]")) {
                    parseInlineRulesList(inlineRest).forEach { rules.add(it) }
                    inRulesSection = false
                }
                continue
            }

            if (inRulesSection) {
                if (isTopLevelKey) {
                    inRulesSection = false
                    continue
                }
                if (trimmed.startsWith("-")) {
                    ClashRule.fromLine(trimmed)?.let { rules.add(it) }
                }
            }
        }
        return rules
    }

    private fun parseInlineRulesList(inlineList: String): List<ClashRule> {
        return try {
            val loaded = Yaml().load<Any>(inlineList) as? List<*> ?: return emptyList()
            loaded.mapNotNull { item -> item?.toString()?.let { ClashRule.fromLine(it) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRules(yamlContent: String, newRules: List<ClashRule>): String {
        val rendered = buildString {
            appendLine("rules:")
            for (rule in newRules) {
                appendLine(rule.toRuleLine())
            }
        }.trimEnd()
        return replaceTopLevelSection(yamlContent, "rules", rendered)
    }

    fun extractAvailablePolicies(yamlContent: String): List<String> {
        val policies = linkedSetOf("DIRECT", "REJECT")
        val root = loadYamlMap(yamlContent)
        if (root != null) {
            (root["proxy-groups"] as? List<*>)?.forEach { group ->
                val name = (group as? Map<*, *>)?.get("name")?.toString()?.trim()
                if (!name.isNullOrEmpty()) policies.add(name)
            }
            (root["proxies"] as? List<*>)?.forEach { proxy ->
                val name = (proxy as? Map<*, *>)?.get("name")?.toString()?.trim()
                if (!name.isNullOrEmpty()) policies.add(name)
            }
            return policies.toList()
        }

        // Fallback line scanner for incomplete YAML
        var currentSection = ""
        for (line in sanitizeYaml(yamlContent).lines()) {
            val trimmed = line.trim()
            if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") && trimmed.contains(":")) {
                currentSection = trimmed.substringBefore(":").trim()
                continue
            }
            if ((currentSection == "proxy-groups" || currentSection == "proxies") &&
                (trimmed.startsWith("- name:") || trimmed.startsWith("name:"))
            ) {
                val namePart = trimmed.substringAfter("name:").trim().trim('"', '\'')
                if (namePart.isNotEmpty()) policies.add(namePart)
            }
        }
        return policies.toList()
    }

    /**
     * Extracts proxy definitions while preserving nested maps (`ws-opts`, `reality-opts`, `grpc-opts`, `headers`)
     * and typed scalar values.
     */
    fun extractProxies(yamlContent: String): List<Map<String, Any>> {
        val root = loadYamlMap(yamlContent) ?: return emptyList()
        val rawProxies = root["proxies"] as? List<*> ?: return emptyList()
        return rawProxies.mapNotNull { entry ->
            normalizeYamlMap(entry)
        }
    }

    /**
     * Saves updated proxies back into the YAML text using structured SnakeYAML serialization
     * and reconciles `proxy-groups` references for renamed, removed, or newly added nodes.
     */
    fun saveProxies(yamlContent: String, newProxies: List<Map<String, Any>>): String {
        val sanitized = sanitizeYaml(yamlContent)
        val oldProxies = extractProxies(sanitized)
        val cleanedProxies = newProxies.mapNotNull { normalizeYamlMap(it) }

        val proxiesSection = dumpYamlSection("proxies", cleanedProxies)
        var updatedYaml = replaceTopLevelSection(sanitized, "proxies", proxiesSection)
        updatedYaml = reconcileProxyGroups(updatedYaml, oldProxies, cleanedProxies)
        return updatedYaml
    }

    private fun reconcileProxyGroups(
        yamlContent: String,
        oldProxies: List<Map<String, Any>>,
        newProxies: List<Map<String, Any>>
    ): String {
        val oldNames = oldProxies.mapNotNull { it["name"]?.toString()?.takeIf { n -> n.isNotBlank() } }
        val newNames = newProxies.mapNotNull { it["name"]?.toString()?.takeIf { n -> n.isNotBlank() } }
        if (oldNames == newNames) return yamlContent

        val oldSet = oldNames.toSet()
        val newSet = newNames.toSet()

        // Pair positional renames if the list size matches and only names changed at those indices
        val renameMap = mutableMapOf<String, String>()
        if (oldNames.size == newNames.size) {
            for (i in oldNames.indices) {
                if (oldNames[i] != newNames[i] && oldNames[i] !in newSet) {
                    renameMap[oldNames[i]] = newNames[i]
                }
            }
        }

        val removedNames = oldSet - newSet - renameMap.keys
        val addedNames = newNames.filter { it !in oldSet && it !in renameMap.values }
        if (renameMap.isEmpty() && removedNames.isEmpty() && addedNames.isEmpty()) return yamlContent

        val root = loadYamlMap(yamlContent) ?: return yamlContent
        val rawGroups = root["proxy-groups"] as? List<*> ?: return yamlContent
        val normalizedGroups = rawGroups.mapNotNull { normalizeYamlMap(it)?.toMutableMap() }
        if (normalizedGroups.isEmpty()) return yamlContent

        val groupNames = normalizedGroups.mapNotNull { it["name"]?.toString() }.toSet()
        var anyModified = false

        for (group in normalizedGroups) {
            val currentList = (group["proxies"] as? List<*>)
                ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                ?: continue

            val hadProxyNodes = currentList.any { it in oldSet || (it !in builtInPolicies && it !in groupNames) }
            val nextList = LinkedHashSet<String>()
            var groupModified = false

            for (entry in currentList) {
                val renamed = renameMap[entry]
                when {
                    renamed != null -> {
                        nextList.add(renamed)
                        groupModified = true
                    }
                    entry in removedNames -> {
                        groupModified = true
                    }
                    else -> nextList.add(entry)
                }
            }

            if (addedNames.isNotEmpty() && hadProxyNodes) {
                val builtInsAtTail = nextList.filter { it in builtInPolicies }
                val nonBuiltIns = nextList.filterNot { it in builtInPolicies }
                nextList.clear()
                nextList.addAll(nonBuiltIns)
                nextList.addAll(addedNames)
                nextList.addAll(builtInsAtTail)
                groupModified = true
            }

            if (nextList.isEmpty()) {
                nextList.add("DIRECT")
                groupModified = true
            }

            if (groupModified) {
                group["proxies"] = nextList.toList()
                anyModified = true
            }
        }

        if (!anyModified) return yamlContent
        val groupsSection = dumpYamlSection("proxy-groups", normalizedGroups)
        return replaceTopLevelSection(yamlContent, "proxy-groups", groupsSection)
    }

    private fun loadYamlMap(yamlContent: String): Map<String, Any>? {
        return try {
            val loaded = Yaml().load<Any>(sanitizeYaml(yamlContent))
            normalizeYamlMap(loaded)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeYamlMap(value: Any?): Map<String, Any>? {
        val map = value as? Map<*, *> ?: return null
        val out = linkedMapOf<String, Any>()
        for ((k, v) in map) {
            if (k != null && v != null) {
                normalizeYamlValue(v)?.let { out[k.toString()] = it }
            }
        }
        return out
    }

    private fun normalizeYamlValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is Map<*, *> -> normalizeYamlMap(value)
            is List<*> -> value.mapNotNull { normalizeYamlValue(it) }
            is Date -> Instant.ofEpochMilli(value.time).toString()
            else -> value
        }
    }
}
