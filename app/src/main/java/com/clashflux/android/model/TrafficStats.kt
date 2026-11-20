package com.clashflux.android.model

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.serialization.Serializable

@Serializable
data class TrafficStats(
    val up: Long = 0,
    val down: Long = 0
) {
    fun totalBytes(): Long = (up.coerceAtLeast(0L) + down.coerceAtLeast(0L))
    fun formatUpSpeed(): String = formatBytes(up) + "/s"
    fun formatDownSpeed(): String = formatBytes(down) + "/s"
    fun formatTotal(): String = formatBytes(totalBytes())

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            return String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups), units[digitGroups])
        }
    }
}

enum class ProxyMode(val label: String) {
    Rule("Rule"),
    Global("Global"),
    Direct("Direct");

    val coreValue: String get() = name.lowercase()
}

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting
}
