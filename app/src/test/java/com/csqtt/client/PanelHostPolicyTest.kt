package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Векторы обязаны совпадать с bash-векторами `is_dns_hostname` / `is_private_ipv4`
 * в `app/src/main/assets/deploy.sh` (Task 3 плана).
 */
class PanelHostPolicyTest {

    @Test
    fun `normalizeHost trims and lowercases`() {
        assertEquals("panel.example.com", PanelHostPolicy.normalizeHost("Panel.Example.COM"))
        assertEquals("panel.example.com", PanelHostPolicy.normalizeHost("  panel.example.com  "))
        assertEquals("", PanelHostPolicy.normalizeHost("   "))
        assertEquals("203.0.113.7", PanelHostPolicy.normalizeHost(" 203.0.113.7 "))
    }

    @Test
    fun `isDomainHost accepts names whose last label has a letter`() {
        listOf("panel.example.com", "localhost", "a.b.c.d.e").forEach { host ->
            assertTrue("ожидался домен: $host", PanelHostPolicy.isDomainHost(host))
        }
    }

    @Test
    fun `isDomainHost rejects addresses and malformed names`() {
        listOf(
            "203.0.113.7",
            "1.2.3.4.5",
            "-bad.example.com",
            "bad-.example.com",
            "bad..example.com",
            ".example.com",
            "example.com.",
            "",
            "  ",
        ).forEach { host ->
            assertFalse("ожидался не-домен: '$host'", PanelHostPolicy.isDomainHost(host))
        }
    }

    @Test
    fun `isDomainHost normalizes case and surrounding spaces`() {
        assertTrue(PanelHostPolicy.isDomainHost("  Panel.Example.COM  "))
    }

    @Test
    fun `isPrivateHost rejects private and link local ranges`() {
        listOf(
            "192.168.1.5",
            "10.0.0.1",
            "172.16.0.1",
            "127.0.0.1",
            "100.64.0.1",
            "169.254.1.1",
            "198.19.1.1",
            "198.18.0.1",
            "0.0.0.1",
            "224.0.0.1",
            "255.255.255.255",
        ).forEach { host ->
            assertTrue("ожидался частный адрес: $host", PanelHostPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `isPrivateHost allows public addresses and names`() {
        listOf("203.0.113.7", "panel.example.com", "8.8.8.8", "").forEach { host ->
            assertFalse("ожидался публичный или домен: '$host'", PanelHostPolicy.isPrivateHost(host))
        }
    }

    @Test
    fun `webPortFor forces 443 for a domain regardless of manual port`() {
        assertEquals(443, PanelHostPolicy.webPortFor("panel.example.com", true, "46002"))
        assertEquals(443, PanelHostPolicy.webPortFor("panel.example.com", true, "abc"))
        assertEquals(443, PanelHostPolicy.webPortFor("panel.example.com", false, "46002"))
    }

    @Test
    fun `webPortFor keeps the manual port for an address`() {
        assertEquals(46002, PanelHostPolicy.webPortFor("203.0.113.7", false, "46099"))
        assertEquals(46099, PanelHostPolicy.webPortFor("203.0.113.7", true, "46099"))
        assertEquals(46002, PanelHostPolicy.webPortFor("203.0.113.7", true, "abc"))
    }

    @Test
    fun `buildPanelUrl omits the port for a domain`() {
        assertEquals(
            "https://panel.example.com/uuid",
            PanelHostPolicy.buildPanelUrl("panel.example.com", 443, "/uuid"),
        )
        assertEquals(
            "https://panel.example.com",
            PanelHostPolicy.buildPanelUrl("panel.example.com", 443, ""),
        )
    }

    @Test
    fun `buildPanelUrl keeps the port for an address`() {
        assertEquals(
            "https://203.0.113.7:46002/uuid",
            PanelHostPolicy.buildPanelUrl("203.0.113.7", 46002, "/uuid"),
        )
        assertEquals(
            "https://203.0.113.7:46002/uuid",
            PanelHostPolicy.buildPanelUrl("203.0.113.7", 46002, "uuid"),
        )
    }

    @Test
    fun `buildPanelUrl falls back to IP placeholder on a blank host`() {
        assertEquals(
            "https://IP:46002/uuid",
            PanelHostPolicy.buildPanelUrl("", 46002, "/uuid"),
        )
    }
}
