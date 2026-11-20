package com.clashflux.android.model

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionMetadata(
    val network: String = "tcp",
    val type: String = "HTTP",
    val sourceIP: String = "",
    val sourcePort: String = "",
    val host: String = "",
    val destinationIP: String = "",
    val destinationPort: String = "",
    val process: String = "",
    val processPath: String = "",
    val uid: Int = 0
)

@Serializable
data class ConnectionItem(
    val id: String,
    val metadata: ConnectionMetadata = ConnectionMetadata(),
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val rule: String = "",
    val rulePayload: String = "",
    val chains: List<String> = emptyList()
)

@Serializable
data class LogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String, // info, warning, error, debug
    val payload: String,
    val timeText: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isBypassed: Boolean = false
)
