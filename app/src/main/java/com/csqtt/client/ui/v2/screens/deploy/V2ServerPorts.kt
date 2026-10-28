package com.csqtt.client.ui.v2.screens.deploy

import com.csqtt.client.CsqttConstants

internal data class EffectiveServerPorts(
    val ssh: Int,
    val peer: Int,
    val web: Int,
)

internal fun resolveServerPorts(
    manualPorts: Boolean,
    sshPort: String,
    peerPort: String,
    webPort: String,
): EffectiveServerPorts {
    // SSH belongs to the VPS connection; the manual switch only controls Peer/Web.
    val ssh = sshPort.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: CsqttConstants.Network.DEFAULT_SSH_PORT
    if (!manualPorts) {
        return EffectiveServerPorts(
            ssh = ssh,
            peer = CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
            web = CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
        )
    }
    return EffectiveServerPorts(
        ssh = ssh,
        peer = peerPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT,
        web = webPort.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT,
    )
}

