package com.clashflux.android.model

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class ClashRule(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // DOMAIN, DOMAIN-SUFFIX, DOMAIN-KEYWORD, IP-CIDR, IP-CIDR6, GEOIP, GEOSITE, PROCESS-NAME, AND, OR, NOT, MATCH
    val payload: String = "", // e.g. challenges.cloudflare.com, 192.168.0.0/16, CN, ((DOMAIN,a.com),(NETWORK,tcp))
    val policy: String, // e.g. DIRECT, REJECT, 🚀 节点选择
    val noResolve: Boolean = false,
    val isCustom: Boolean = false
) {
    fun toRuleLine(): String {
        val customMarker = if (isCustom) " # custom" else ""
        val ruleExpr = if (type.equals("MATCH", ignoreCase = true)) {
            "MATCH,$policy"
        } else {
            val suffix = if (noResolve) ",no-resolve" else ""
            "$type,$payload,$policy$suffix"
        }
        val escaped = ruleExpr.replace("'", "''")
        return "  - '$escaped'$customMarker"
    }

    companion object {
        private fun stripControlChars(s: String): String {
            return s.filter { it >= ' ' || it == '\t' }
        }

        private fun stripInlineComment(line: String): Pair<String, Boolean> {
            var inSingle = false
            var inDouble = false
            for (i in line.indices) {
                val c = line[i]
                when {
                    c == '\'' && !inDouble -> inSingle = !inSingle
                    c == '"' && !inSingle -> inDouble = !inDouble
                    c == '#' && !inSingle && !inDouble -> {
                        val comment = line.substring(i)
                        return line.substring(0, i) to comment.contains("custom", ignoreCase = true)
                    }
                }
            }
            return line to false
        }

        private fun unquoteOuter(value: String): String {
            val trimmed = value.trim()
            if (trimmed.length >= 2) {
                if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
                    return trimmed.substring(1, trimmed.length - 1).replace("''", "'").trim()
                }
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    return trimmed.substring(1, trimmed.length - 1).trim()
                }
            }
            return trimmed
        }

        /**
         * Split on top-level commas only so compound rules like AND,((DOMAIN,a),(NETWORK,tcp)),PROXY keep their payload intact.
         */
        private fun splitTopLevelCommas(input: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var parenDepth = 0
            var inSingle = false
            var inDouble = false
            for (c in input) {
                when {
                    c == '\'' && !inDouble -> {
                        inSingle = !inSingle
                        current.append(c)
                    }
                    c == '"' && !inSingle -> {
                        inDouble = !inDouble
                        current.append(c)
                    }
                    !inSingle && !inDouble && c == '(' -> {
                        parenDepth++
                        current.append(c)
                    }
                    !inSingle && !inDouble && c == ')' -> {
                        if (parenDepth > 0) parenDepth--
                        current.append(c)
                    }
                    !inSingle && !inDouble && parenDepth == 0 && c == ',' -> {
                        parts.add(stripControlChars(current.toString()).trim())
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotEmpty()) {
                parts.add(stripControlChars(current.toString()).trim())
            }
            return parts.map { unquoteOuter(it) }
        }

        fun fromLine(rawLine: String): ClashRule? {
            val sanitized = stripControlChars(rawLine)
            val (beforeComment, isCustom) = stripInlineComment(sanitized)
            var cleanLine = beforeComment.trim()
            if (cleanLine.isEmpty()) return null
            if (cleanLine.startsWith("-")) {
                cleanLine = cleanLine.removePrefix("-").trim()
            }
            if (cleanLine.isEmpty()) return null
            cleanLine = unquoteOuter(cleanLine)

            val parts = splitTopLevelCommas(cleanLine)
            if (parts.size >= 2) {
                val type = parts[0].uppercase()
                if (type == "MATCH") {
                    return ClashRule(
                        type = "MATCH",
                        payload = "",
                        policy = parts[1],
                        isCustom = isCustom
                    )
                } else if (parts.size >= 3) {
                    val hasNoResolve = parts.drop(3).any { it.equals("no-resolve", ignoreCase = true) }
                    return ClashRule(
                        type = type,
                        payload = parts[1],
                        policy = parts[2],
                        noResolve = hasNoResolve,
                        isCustom = isCustom
                    )
                }
            }
            return null
        }
    }
}
