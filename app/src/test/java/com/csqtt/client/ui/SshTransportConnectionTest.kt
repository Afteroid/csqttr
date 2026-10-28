package com.csqtt.client.ui.legacy

import java.io.IOException
import java.net.SocketTimeoutException
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.Transport
import net.schmizz.sshj.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.*

class SshTransportConnectionTest {
    private fun client(): SSHClient = mock(SSHClient::class.java).also {
        `when`(it.transport).thenReturn(mock(Transport::class.java))
    }

    @Test
    fun `banner timeout closes the socket and retries with a fresh client`() {
        val first = client()
        val second = client()
        doThrow(TransportException(SocketTimeoutException("Connection timed out")))
            .`when`(first).connect("server.example", 5052)
        val clients = ArrayDeque(listOf(first, second))
        var retries = 0

        assertSame(second, connectSshTransport("server.example", 5052, { clients.removeFirst() }, { retries++ }))
        assertEquals(1, retries)
        verify(first).disconnect()
        verify(second, never()).disconnect()
        for (ssh in listOf(first, second)) {
            verify(ssh).setConnectTimeout(SSH_HANDSHAKE_TIMEOUT_MS)
            verify(ssh).setTimeout(SSH_HANDSHAKE_TIMEOUT_MS)
            verify(ssh.transport).setTimeoutMs(SSH_HANDSHAKE_TIMEOUT_MS)
        }
    }

    @Test
    fun `repeated timeout stops after two clients and closes both`() {
        val clients = List(2) { client() }
        clients.forEach {
            doThrow(TransportException(SocketTimeoutException("Connection timed out")))
                .`when`(it).connect("server.example", 5052)
        }
        var created = 0
        val error = assertThrows(IOException::class.java) {
            connectSshTransport("server.example", 5052, { clients[created++] })
        }
        assertEquals(2, created)
        assertEquals("SSH handshake timeout at server.example:5052 after two connection attempts", error.message)
        clients.forEach { verify(it).disconnect() }
    }

    @Test
    fun `host key rejection is closed and never retried`() {
        val ssh = client()
        val rejection = IOException("HostKey changed")
        doThrow(rejection).`when`(ssh).connect("server.example", 5052)
        var created = 0
        assertSame(rejection, assertThrows(IOException::class.java) {
            connectSshTransport("server.example", 5052, { created++; ssh })
        })
        assertEquals(1, created)
        verify(ssh).disconnect()
    }

    @Test
    fun `timed out spelling gets a readable deployment error`() {
        assertEquals("Истекло время ожидания SSH/SFTP", friendlyDeployError("Connection timed out"))
    }
}
