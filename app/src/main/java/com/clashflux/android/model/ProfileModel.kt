package com.clashflux.android.model

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val url: String = "",
    val filePath: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val expireTimestamp: Long = 0,
    val isSelected: Boolean = false
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val progress: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
