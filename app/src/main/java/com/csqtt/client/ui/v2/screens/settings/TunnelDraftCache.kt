package com.csqtt.client.ui.v2.screens.settings

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.csqtt.client.TunnelScreenSnapshot

/** Activity memory only: never puts a decrypted password in saved instance state. */
internal class TunnelDraftCache {
    private var current: TunnelDraft? = null

    fun obtain(snapshot: TunnelScreenSnapshot): TunnelDraft {
        val cached = current
        if (cached != null && cached.profile == snapshot.profile && cached.revision == snapshot.connection.revision) return cached
        return TunnelDraft(snapshot).also { current = it }
    }
}

internal class TunnelDraft(snapshot: TunnelScreenSnapshot) {
    val profile = snapshot.profile
    val revision = snapshot.connection.revision
    val host = mutableStateOf(snapshot.connection.peer)
    val port = mutableStateOf(snapshot.connection.serverPeerPort.toString())
    val password = mutableStateOf(snapshot.connection.connectionPassword)
    val workers = mutableIntStateOf(snapshot.connection.workersPerHash)
}
