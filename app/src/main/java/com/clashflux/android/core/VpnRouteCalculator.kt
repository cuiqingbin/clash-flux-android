package com.clashflux.android.core

import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Pure-Kotlin CIDR math helper for VPN routing and LAN-bypass calculations across all Android
 * API levels (`minSdk 26` complement routes as well as `API 33+` exclusion prefixes).
 */
object VpnRouteCalculator {

    data class CidrRoute(
        val address: InetAddress,
        val prefixLength: Int
    ) {
        val notation: String get() = "${address.hostAddress}/$prefixLength"
    }

    private data class BitRange(
        val network: BigInteger,
        val prefixLength: Int,
        val bitWidth: Int
    ) {
        val start: BigInteger get() = network
        val end: BigInteger get() = network.add(rangeSize(prefixLength, bitWidth)).subtract(BigInteger.ONE)

        fun toCidrRoute(): CidrRoute {
            val byteLen = bitWidth / 8
            val raw = network.toByteArray()
            val fixed = ByteArray(byteLen)
            val copyCount = minOf(raw.size, byteLen)
            System.arraycopy(raw, raw.size - copyCount, fixed, byteLen - copyCount, copyCount)
            return CidrRoute(InetAddress.getByAddress(fixed), prefixLength)
        }
    }

    private val IPV4_TUN_PORTAL_SUBNET = "172.19.0.0/30"
    private val IPV6_TUN_PORTAL_SUBNET = "fdfe:dcba:9876::/126"

    private val IPV4_PRIVATE_EXCLUSIONS = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4"
    )

    private val IPV6_PRIVATE_EXCLUSIONS = listOf(
        "fc00::/7",
        "fe80::/10",
        "ff00::/8"
    )

    /**
     * Exact IPv4 prefixes to exclude on Android 13+ (`VpnService.Builder.excludeRoute`) when
     * LAN bypass is enabled. Automatically keeps `172.19.0.0/30` (the TUN gateway & DNS portal)
     * routed through TUN even though it sits inside `172.16.0.0/12`.
     */
    fun ipv4ExcludeRoutesForBypass(): List<CidrRoute> {
        val excludes = IPV4_PRIVATE_EXCLUSIONS.map { parseBitRange(it, 32) }
        val keep = listOf(parseBitRange(IPV4_TUN_PORTAL_SUBNET, 32))
        return subtractLists(excludes, keep).map { it.toCidrRoute() }
    }

    /**
     * Exact IPv4 inclusion routes covering all public IPv4 space + `172.19.0.0/30` (TUN DNS) +
     * `198.18.0.0/15` (Fake-IP) for Android 8–12 (`API 26–32`) where `excludeRoute` is absent.
     */
    fun ipv4IncludeRoutesForLegacyBypass(): List<CidrRoute> {
        val full = listOf(parseBitRange("0.0.0.0/0", 32))
        val effectiveExcludes = subtractLists(
            IPV4_PRIVATE_EXCLUSIONS.map { parseBitRange(it, 32) },
            listOf(parseBitRange(IPV4_TUN_PORTAL_SUBNET, 32))
        )
        return subtractLists(full, effectiveExcludes).map { it.toCidrRoute() }
    }

    /**
     * Exact IPv6 prefixes to exclude on Android 13+ when IPv6 and LAN bypass are both enabled,
     * preserving `fdfe:dcba:9876::/126` for the IPv6 TUN DNS portal.
     */
    fun ipv6ExcludeRoutesForBypass(): List<CidrRoute> {
        val excludes = IPV6_PRIVATE_EXCLUSIONS.map { parseBitRange(it, 128) }
        val keep = listOf(parseBitRange(IPV6_TUN_PORTAL_SUBNET, 128))
        return subtractLists(excludes, keep).map { it.toCidrRoute() }
    }

    /**
     * Compact IPv6 inclusion routes for Android 8–12 when IPv6 and LAN bypass are both enabled:
     * covers all global unicast addresses (`2000::/3`) plus the local IPv6 TUN DNS subnet.
     */
    fun ipv6IncludeRoutesForLegacyBypass(): List<CidrRoute> {
        return listOf(
            parseBitRange("2000::/3", 128).toCidrRoute(),
            parseBitRange(IPV6_TUN_PORTAL_SUBNET, 128).toCidrRoute()
        )
    }

    internal fun ipv4Contains(routes: List<CidrRoute>, host: String): Boolean {
        val target = toUnsignedInt(InetAddress.getByName(host))
        return routes.any { route ->
            if (route.address !is Inet4Address) return@any false
            val bitWidth = 32
            val net = toUnsignedInt(route.address).and(prefixMask(route.prefixLength, bitWidth))
            val end = net.add(rangeSize(route.prefixLength, bitWidth)).subtract(BigInteger.ONE)
            target >= net && target <= end
        }
    }

    internal fun ipv6Contains(routes: List<CidrRoute>, host: String): Boolean {
        val target = toUnsignedInt(InetAddress.getByName(host))
        return routes.any { route ->
            if (route.address !is Inet6Address) return@any false
            val bitWidth = 128
            val net = toUnsignedInt(route.address).and(prefixMask(route.prefixLength, bitWidth))
            val end = net.add(rangeSize(route.prefixLength, bitWidth)).subtract(BigInteger.ONE)
            target >= net && target <= end
        }
    }

    private fun subtractLists(bases: List<BitRange>, excludes: List<BitRange>): List<BitRange> {
        var current = bases
        for (ex in excludes) {
            current = current.flatMap { base -> subtractSingle(base, ex) }
        }
        return current
    }

    private fun subtractSingle(base: BitRange, exclude: BitRange): List<BitRange> {
        if (exclude.end < base.start || exclude.start > base.end) {
            return listOf(base)
        }
        if (exclude.start <= base.start && exclude.end >= base.end) {
            return emptyList()
        }
        if (base.prefixLength >= base.bitWidth) {
            return emptyList()
        }
        val nextPrefix = base.prefixLength + 1
        val splitBitIndex = base.bitWidth - nextPrefix
        val left = BitRange(base.network, nextPrefix, base.bitWidth)
        val right = BitRange(base.network.setBit(splitBitIndex), nextPrefix, base.bitWidth)
        return subtractSingle(left, exclude) + subtractSingle(right, exclude)
    }

    private fun parseBitRange(cidr: String, expectedBits: Int): BitRange {
        val slashIdx = cidr.indexOf('/')
        require(slashIdx > 0) { "Invalid CIDR: $cidr" }
        val addr = InetAddress.getByName(cidr.substring(0, slashIdx))
        val prefix = cidr.substring(slashIdx + 1).toInt()
        require(prefix in 0..expectedBits) { "Invalid prefix length $prefix for $expectedBits-bit CIDR" }
        val actualBits = addr.address.size * 8
        require(actualBits == expectedBits) { "Expected $expectedBits-bit address, got $cidr" }
        val mask = prefixMask(prefix, expectedBits)
        return BitRange(toUnsignedInt(addr).and(mask), prefix, expectedBits)
    }

    private fun toUnsignedInt(addr: InetAddress): BigInteger {
        return BigInteger(1, addr.address)
    }

    private fun prefixMask(prefixLength: Int, bitWidth: Int): BigInteger {
        if (prefixLength == 0) return BigInteger.ZERO
        return BigInteger.ONE.shiftLeft(bitWidth).subtract(BigInteger.ONE)
            .xor(BigInteger.ONE.shiftLeft(bitWidth - prefixLength).subtract(BigInteger.ONE))
    }

    private fun rangeSize(prefixLength: Int, bitWidth: Int): BigInteger {
        return BigInteger.ONE.shiftLeft(bitWidth - prefixLength)
    }
}
