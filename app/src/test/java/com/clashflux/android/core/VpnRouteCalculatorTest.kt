package com.clashflux.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRouteCalculatorTest {

    @Test
    fun `ipv4ExcludeRoutesForBypass excludes LAN ranges while preserving TUN DNS subnet`() {
        val excluded = VpnRouteCalculator.ipv4ExcludeRoutesForBypass()

        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "10.0.0.1"))
        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "192.168.1.1"))
        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "172.16.0.1"))
        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "172.18.255.254"))
        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "172.31.255.254"))
        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "169.254.10.20"))
        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "127.0.0.1"))
        assertTrue(VpnRouteCalculator.ipv4Contains(excluded, "224.0.0.1"))

        // TUN interface (172.19.0.1) and DNS portal (172.19.0.2) must NOT be excluded
        assertFalse(VpnRouteCalculator.ipv4Contains(excluded, "172.19.0.1"))
        assertFalse(VpnRouteCalculator.ipv4Contains(excluded, "172.19.0.2"))

        // Public internet & Fake-IP must NOT be excluded
        assertFalse(VpnRouteCalculator.ipv4Contains(excluded, "1.1.1.1"))
        assertFalse(VpnRouteCalculator.ipv4Contains(excluded, "8.8.8.8"))
        assertFalse(VpnRouteCalculator.ipv4Contains(excluded, "198.18.0.1"))
    }

    @Test
    fun `ipv4IncludeRoutesForLegacyBypass covers public space and TUN DNS while omitting LAN`() {
        val included = VpnRouteCalculator.ipv4IncludeRoutesForLegacyBypass()

        // Public space + Fake-IP + TUN DNS must be included
        assertTrue(VpnRouteCalculator.ipv4Contains(included, "1.1.1.1"))
        assertTrue(VpnRouteCalculator.ipv4Contains(included, "8.8.8.8"))
        assertTrue(VpnRouteCalculator.ipv4Contains(included, "172.19.0.1"))
        assertTrue(VpnRouteCalculator.ipv4Contains(included, "172.19.0.2"))
        assertTrue(VpnRouteCalculator.ipv4Contains(included, "198.18.0.5"))

        // Private LAN / loopback / link-local / multicast must be omitted
        assertFalse(VpnRouteCalculator.ipv4Contains(included, "10.0.0.1"))
        assertFalse(VpnRouteCalculator.ipv4Contains(included, "192.168.50.10"))
        assertFalse(VpnRouteCalculator.ipv4Contains(included, "172.16.0.1"))
        assertFalse(VpnRouteCalculator.ipv4Contains(included, "172.31.1.1"))
        assertFalse(VpnRouteCalculator.ipv4Contains(included, "169.254.1.1"))
        assertFalse(VpnRouteCalculator.ipv4Contains(included, "127.0.0.1"))
        assertFalse(VpnRouteCalculator.ipv4Contains(included, "224.0.0.1"))
    }

    @Test
    fun `ipv6 bypass routes exclude private ULA and link-local while keeping TUN DNS and global unicast`() {
        val excluded = VpnRouteCalculator.ipv6ExcludeRoutesForBypass()
        assertTrue(VpnRouteCalculator.ipv6Contains(excluded, "fc00::1"))
        assertTrue(VpnRouteCalculator.ipv6Contains(excluded, "fd12:3456:789a::1"))
        assertTrue(VpnRouteCalculator.ipv6Contains(excluded, "fe80::1"))
        assertTrue(VpnRouteCalculator.ipv6Contains(excluded, "ff02::1"))
        assertFalse(VpnRouteCalculator.ipv6Contains(excluded, "fdfe:dcba:9876::1"))
        assertFalse(VpnRouteCalculator.ipv6Contains(excluded, "fdfe:dcba:9876::2"))
        assertFalse(VpnRouteCalculator.ipv6Contains(excluded, "2606:4700:4700::1111"))

        val legacyIncluded = VpnRouteCalculator.ipv6IncludeRoutesForLegacyBypass()
        assertTrue(VpnRouteCalculator.ipv6Contains(legacyIncluded, "2606:4700:4700::1111"))
        assertTrue(VpnRouteCalculator.ipv6Contains(legacyIncluded, "fdfe:dcba:9876::1"))
        assertTrue(VpnRouteCalculator.ipv6Contains(legacyIncluded, "fdfe:dcba:9876::2"))
        assertFalse(VpnRouteCalculator.ipv6Contains(legacyIncluded, "fe80::1"))
        assertFalse(VpnRouteCalculator.ipv6Contains(legacyIncluded, "fc00::1"))
        assertFalse(VpnRouteCalculator.ipv6Contains(legacyIncluded, "ff02::1"))
    }
}
