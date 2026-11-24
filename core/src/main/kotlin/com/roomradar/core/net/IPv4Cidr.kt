package com.roomradar.core.net

/**
 * Small, allocation-light IPv4 CIDR helper.
 *
 * The product must never sweep an entire /8 or /16: hotel networks can be huge and active scanning
 * would be slow, abusive, and sometimes illegal. We therefore default to the /24 containing the
 * current address even when the interface prefix is shorter.
 */
data class IPv4Cidr(
    val address: UInt,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..32) { "prefixLength must be 0..32, was $prefixLength" }
    }

    val mask: UInt = when (prefixLength) {
        0 -> 0u
        32 -> 0xFFFFFFFFu
        else -> (0xFFFFFFFFu shl (32 - prefixLength))
    }

    val network: UInt = address and mask
    val broadcast: UInt = network or mask.inv()

    fun contains(ip: UInt): Boolean = (ip and mask) == network

    /** Host addresses in this CIDR, excluding network/broadcast for ordinary LAN prefixes. */
    fun hosts(): Sequence<String> {
        if (prefixLength == 32) return sequenceOf(formatIPv4(address))
        if (prefixLength == 31) {
            return sequenceOf(formatIPv4(network), formatIPv4(network + 1u))
        }
        val first = network + 1u
        val last = broadcast - 1u
        return sequence {
            var current = first
            while (current <= last && current != 0u) {
                yield(formatIPv4(current))
                if (current == last) break
                current += 1u
            }
        }
    }

    companion object {
        fun parse(cidr: String): IPv4Cidr {
            val parts = cidr.trim().split("/")
            require(parts.size == 2) { "Expected a.b.c.d/p, got $cidr" }
            val ip = parseIPv4(parts[0])
            val prefix = parts[1].toInt()
            return IPv4Cidr(ip, prefix)
        }

        fun fromIpAndPrefix(ip: String, prefixLength: Int): IPv4Cidr =
            IPv4Cidr(parseIPv4(ip), prefixLength)

        /** The bounded /24 to scan for the common same-subnet camera deployment. */
        fun local24(ip: String): IPv4Cidr {
            val value = parseIPv4(ip)
            return IPv4Cidr(value and 0xFFFFFF00u, 24)
        }
    }
}

fun parseIPv4(input: String): UInt {
    val parts = input.trim().split(".")
    require(parts.size == 4) { "Invalid IPv4 address: $input" }
    var result = 0u
    for (part in parts) {
        val n = part.toIntOrNull() ?: throw IllegalArgumentException("Invalid IPv4 octet: $part")
        require(n in 0..255) { "IPv4 octet out of range: $part" }
        result = (result shl 8) or n.toUInt()
    }
    return result
}

fun formatIPv4(address: UInt): String =
    "${(address shr 24) and 0xFFu}." +
        "${(address shr 16) and 0xFFu}." +
        "${(address shr 8) and 0xFFu}." +
        "${address and 0xFFu}"

/** Returns null instead of throwing for strings coming from OS APIs or remote agents. */
fun parseIPv4OrNull(input: String?): UInt? = try {
    input?.takeIf { it.isNotBlank() }?.let(::parseIPv4)
} catch (_: IllegalArgumentException) {
    null
}
