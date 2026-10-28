package com.csqtt.client

internal object WorkerCountPolicy {
    fun normalize(
        requested: Int,
        maximum: Int = CsqttConstants.Tunnel.MAX_WORKERS,
    ): Int {
        val group = CsqttConstants.Tunnel.WORKERS_PER_GROUP
        val normalizedMaximum = (maximum.coerceAtLeast(group) / group) * group
        val clamped = requested.coerceIn(group, normalizedMaximum)
        val rounded = (clamped / group) * group
        return rounded.coerceAtLeast(group)
    }

    fun maximumForHashes(hashCount: Int): Int {
        val count = hashCount.coerceIn(1, CsqttConstants.Tunnel.MAX_VK_HASHES)
        return (count * CsqttConstants.Tunnel.WORKERS_PER_GROUP).coerceIn(CsqttConstants.Tunnel.WORKERS_PER_GROUP, CsqttConstants.Tunnel.MAX_WORKERS)
    }

    fun maximumForSources(
        linkMode: Boolean,
        linkHashCount: Int,
        autoHashMode: Boolean,
        manualHashCount: Int,
    ): Int = CsqttConstants.Tunnel.MAX_WORKERS

    fun defaultMaximum(extraWorkers: Boolean = false): Int =
        if (extraWorkers) CsqttConstants.Tunnel.MAX_WORKERS else CsqttConstants.Tunnel.DEFAULT_MAX_WORKERS

    fun normalizeForHashes(requested: Int, hashCount: Int): Int =
        normalize(requested, maximumForHashes(hashCount))

    fun normalizeForHashValues(
        requested: Int,
        hashes: Iterable<String>,
        allowRedistribution: Boolean = false,
    ): Int {
        return normalize(requested, CsqttConstants.Tunnel.MAX_WORKERS)
    }

}
