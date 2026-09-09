package com.benzn.grandtime.wifi

/** How a network is protected, as the QR describes it. */
enum class WifiSecurity { OPEN, WPA, WPA3, WEP }

/** A network described by the QR a phone produces from "share this Wi-Fi". */
data class WifiQrPayload(
    val ssid: String,
    val password: String?,
    val security: WifiSecurity,
    val hidden: Boolean,
)

/**
 * Reads the Wi-Fi Alliance's `WIFI:S:<ssid>;T:<type>;P:<password>;;`, which is what both Android
 * and iOS put in a shared-network QR.
 *
 * Kept free of Android so it can be driven by tests. The parts worth writing carefully are the
 * ones a regex gets wrong: fields come in any order, and `;` `:` `,` `\` inside a value are
 * backslash-escaped — a site network called `Level 3; Wing B` is ordinary, and truncating it at
 * the semicolon would join the wrong network while reporting success.
 */
object WifiQrParser {

    private const val PREFIX = "wifi:"

    /** Null for anything that is not a Wi-Fi QR we can act on. */
    fun parse(raw: String): WifiQrPayload? {
        val text = raw.trim()
        if (!text.lowercase().startsWith(PREFIX)) return null

        val fields = mutableMapOf<Char, String>()
        for (field in splitUnescaped(text.substring(PREFIX.length), ';')) {
            val at = field.indexOf(':')
            if (at <= 0) continue
            val key = field.substring(0, at).trim().lowercase()
            if (key.length != 1) continue
            // First occurrence wins; a second S: is a malformed code, not an override.
            fields.putIfAbsent(key[0], unescape(field.substring(at + 1)))
        }

        val ssid = fields['s'].orEmpty()
        if (ssid.isEmpty()) return null
        // `S:"48656c6c6f"` is a hex SSID. Taking the digits literally would join a network nobody
        // named and still say it worked, so this refuses instead of guessing.
        if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"')) return null

        val password = fields['p']?.takeIf { it.isNotEmpty() }
        return WifiQrPayload(
            ssid = ssid,
            password = password,
            security = securityOf(fields['t'], password),
            hidden = fields['h']?.equals("true", ignoreCase = true) == true,
        )
    }

    /**
     * Plenty of generators omit `T`. A password means the network is protected; without one it is
     * open. Guessing WPA for a passwordless network would build a suggestion Android rejects.
     */
    private fun securityOf(type: String?, password: String?): WifiSecurity =
        when (type?.trim()?.uppercase()) {
            "SAE", "WPA3" -> WifiSecurity.WPA3
            "WEP" -> WifiSecurity.WEP
            "NOPASS", "NONE", "" -> WifiSecurity.OPEN
            null -> if (password != null) WifiSecurity.WPA else WifiSecurity.OPEN
            else -> if (password != null) WifiSecurity.WPA else WifiSecurity.OPEN
        }

    /** Split on [sep], ignoring any occurrence preceded by a backslash. */
    private fun splitUnescaped(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { current.append(c).append(s[i + 1]); i += 2 }
                c == sep -> { out.add(current.toString()); current.clear(); i++ }
                else -> { current.append(c); i++ }
            }
        }
        out.add(current.toString())
        return out
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { out.append(s[i + 1]); i += 2 } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
