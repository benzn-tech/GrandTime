package com.benzn.grandtime.wifi

/** How a network is protected, as the QR describes it. */
enum class WifiSecurity {
    OPEN, WPA, WPA3,

    /** Android's suggestion API has no WEP. Carried so the screen can name it. */
    WEP,

    /** WPA2-EAP and friends. Built as a PSK these save and then never connect, which is worse
     *  than a refusal because the operator is told it worked. */
    ENTERPRISE,
}

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

    /** WifiSsid.fromUtf8Text's limit, and it throws rather than truncating. */
    private const val MAX_SSID_BYTES = 32

    /** Null for anything that is not a Wi-Fi QR we can act on. */
    fun parse(raw: String): WifiQrPayload? {
        val text = raw.trim()
        if (!text.lowercase().startsWith(PREFIX)) return null

        val fields = mutableMapOf<Char, String>()
        for (field in splitUnescaped(text.substring(PREFIX.length), ';')) {
            if (field.isBlank()) continue          // the trailing `;;`
            val at = field.indexOf(':')
            // A non-empty field with no key is the wreckage of an unescaped separator inside a
            // value -- `P:ab;cd` leaves `cd` here. Keeping `ab` would save a password that cannot
            // connect, and nothing downstream verifies a passphrase, so the operator would be told
            // "Saved" and simply never get on the network.
            if (at <= 0) return null
            val key = field.substring(0, at).trim().lowercase()
            if (key.length != 1) return null
            // First occurrence wins; a second S: is a malformed code, not an override.
            fields.putIfAbsent(key[0], unescape(field.substring(at + 1)))
        }

        val ssid = fields['s'].orEmpty()
        if (ssid.isEmpty()) return null
        // Refused HERE because WifiNetworkSuggestion.Builder throws IllegalArgumentException for
        // both, on a coroutine, where it takes the process with it. A password autocorrected from
        // `O'Brien` to a curly quote is an ordinary way to arrive.
        if (ssid.toByteArray(Charsets.UTF_8).size > MAX_SSID_BYTES) return null

        val password = fields['p']?.takeIf { it.isNotEmpty() }
        if (password != null && password.any { it.code > 127 }) return null

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
    private fun securityOf(type: String?, password: String?): WifiSecurity {
        val t = type?.trim()?.uppercase()
        return when {
            // `nopass` is a statement, not an absence: the network is open whatever else the
            // code carries. Only a MISSING T has to be inferred from the password.
            t == "NOPASS" || t == "NONE" || t == "" -> WifiSecurity.OPEN
            t == null -> if (password != null) WifiSecurity.WPA else WifiSecurity.OPEN
            t.contains("EAP") -> WifiSecurity.ENTERPRISE
            t == "WEP" -> WifiSecurity.WEP
            t == "SAE" || t == "WPA3" -> WifiSecurity.WPA3
            // WPA, WPA2, WPA/WPA2 and anything else a generator writes: a passphrase network if
            // there is a passphrase, open if there is not. Guessing WPA for a passwordless network
            // builds a suggestion Android rejects.
            else -> if (password != null) WifiSecurity.WPA else WifiSecurity.OPEN
        }
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
