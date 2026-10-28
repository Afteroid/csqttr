package com.csqtt.client.ui.v2.screens.deploy

import org.junit.Assert.assertEquals
import org.junit.Test

class V2ServerPortsTest {
    @Test
    fun `custom SSH port survives saving with automatic Peer and Web ports`() {
        val saved = resolveServerPorts(false, "5052", "47000", "47002")
        assertEquals(5052, saved.ssh)
        assertEquals(46000, saved.peer)
        assertEquals(46002, saved.web)

        val reloaded = resolveServerPorts(false, saved.ssh.toString(), saved.peer.toString(), saved.web.toString())
        assertEquals(saved, reloaded)
    }

    @Test
    fun `changing Peer and Web mode preserves SSH connection port`() {
        val manual = resolveServerPorts(true, "5052", "47000", "47002")
        val automatic = resolveServerPorts(false, "5052", "47000", "47002")
        assertEquals(manual.ssh, automatic.ssh)
        assertEquals(47000, manual.peer)
        assertEquals(47002, manual.web)
    }

    @Test
    fun `empty SSH port uses standard port in both modes`() {
        for (manual in listOf(false, true)) {
            assertEquals(22, resolveServerPorts(manual, "", "46000", "46002").ssh)
        }
    }
}
