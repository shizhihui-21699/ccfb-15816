package com.roomradar.core.detect

/**
 * OUI lookup abstraction.
 *
 * Production apps should ship a dated IEEE MA-L subset or a separately licensed/vendor-curated
 * database and update it. A MAC prefix alone is weak evidence because camera modules are also
 * bought by TV, doorbell and IoT vendors.
 */
fun interface OuiClassifier {
    fun vendorFor(mac: String?): String?

    companion object {
        val EMPTY = OuiClassifier { null }
    }
}

/** Small seed list for tests/demo. Keep OUI evidence low-weight until a maintained DB is wired. */
class SeedOuiClassifier(
    private val prefixToVendor: Map<String, String> = DEFAULT_PREFIXES,
) : OuiClassifier {
    override fun vendorFor(mac: String?): String? {
        val normalized = normalizeOui(mac) ?: return null
        return prefixToVendor.entries.firstOrNull { normalized.startsWith(it.key) }?.value
    }

    companion object {
        private fun normalizeOui(mac: String?): String? {
            if (mac.isNullOrBlank()) return null
            val hex = mac.uppercase().replace(":", "").replace("-", "").replace(".", "")
            return hex.takeIf { it.length >= 6 }?.take(6)
        }

        val DEFAULT_PREFIXES = mapOf(
            // These are intentionally used only as low-weight hints; verify/update in production.
            "00408C" to "Axis Communications",
            "ACCC8E" to "Axis Communications",
            "C056E3" to "Hangzhou Hikvision",
            "1868CB" to "Hangzhou Hikvision",
            "3CEF8C" to "Zhejiang Dahua",
            "E0508B" to "Zhejiang Dahua",
            "9C14EB" to "Zhejiang Dahua",
            "3456FE" to "Xiongmai/XMEye module",
        )
    }
}
