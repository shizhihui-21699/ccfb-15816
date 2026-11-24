package com.roomradar.android.net

import java.io.File

data class NeighborEntry(
    val ip: String,
    val mac: String,
    val device: String?,
)

/**
 * Best-effort ARP/neighbor table reader.
 *
 * Modern Android versions hide or empty `/proc/net/arp` for ordinary apps. mDNS, SSDP and active
 * TCP probing remain necessary; this only enriches results when the OS happens to expose entries.
 */
class NeighborTableReader(
    private val arpFile: File = File("/proc/net/arp"),
) {
    fun read(): List<NeighborEntry> = try {
        if (!arpFile.exists() || !arpFile.canRead()) {
            emptyList()
        } else {
            arpFile.readLines()
                .drop(1)
                .mapNotNull(::parseLine)
        }
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: java.io.IOException) {
        emptyList()
    }

    private fun parseLine(line: String): NeighborEntry? {
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 4) return null
        val ip = columns[0]
        val flags = columns[2]
        val mac = columns[3]
        if (!ip.matches(IP_REGEX)) return null
        if (mac == "00:00:00:00:00:00" || !mac.matches(MAC_REGEX)) return null
        // 0x2 is ATF_COM / a completed neighbor entry. Some kernels still expose permanent entries.
        if (flags != "0x2" && flags != "0x6") return null
        return NeighborEntry(ip = ip, mac = mac.uppercase(), device = columns.getOrNull(5))
    }

    companion object {
        private val IP_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val MAC_REGEX = Regex("""[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}""")
    }
}
