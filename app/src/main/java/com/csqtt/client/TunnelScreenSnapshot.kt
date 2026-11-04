package com.csqtt.client

/** One persisted revision: identity and credentials always belong to the same profile. */
internal data class TunnelScreenSnapshot(
    val profile: Int,
    val name: String,
    val connection: ProfileConnectionSnapshot,
    val hashes: String,
    val hashMode: String,
    val authMode: String,
    val token: String,
    val srtp: String,
    val transport: String,
    val subnet: String,
    val ipLimit: String,
    val hashResults: String,
    val removedOwnerHashes: Set<String>,
    val autoJsAcknowledged: Boolean,
    val tcpAcknowledged: Boolean,
    val goobyEnabled: Boolean = false,
    val goobyStrategy: String = GoobyPolicy.DEFAULT_STRATEGY,
)
