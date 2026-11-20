package com.clashflux.android.model

import kotlinx.serialization.Serializable

@Serializable
data class ProxyNode(
    val name: String,
    val type: String,
    val delay: Int? = null,
    val isTesting: Boolean = false
) {
    fun isAlive(): Boolean = delay != null && delay > 0
}

@Serializable
data class ProxyGroup(
    val name: String,
    val type: String,
    val now: String,
    val all: List<ProxyNode> = emptyList()
)
