package com.phonemic.app.audio

/**
 * Accepts a human-typed IPv4 address and returns a clean, canonical form.
 *
 * This intentionally rejects values that cannot identify a PC on a local
 * network, such as 0.0.0.0, loopback, and multicast/reserved ranges. Leading
 * zeroes in an octet are accepted and normalized because some keyboards and
 * users naturally type them.
 */
internal object Ipv4Address {
    private val ipv4Pattern = Regex("""^[0-9]{1,3}(\.[0-9]{1,3}){3}$""")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (!ipv4Pattern.matches(trimmed)) return null

        val octets = trimmed.split('.').map { part ->
            part.toIntOrNull() ?: return null
        }
        if (octets.size != 4) return null
        if (octets.any { it !in 0..255 }) return null

        // A destination that is unspecified, loopback, multicast, or reserved
        // cannot be the PC's LAN address.
        if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224) return null

        return octets.joinToString(".")
    }
}
