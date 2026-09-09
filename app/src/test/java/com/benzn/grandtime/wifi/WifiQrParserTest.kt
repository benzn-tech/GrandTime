package com.benzn.grandtime.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR a phone produces from "share this Wi-Fi". The format is the Wi-Fi Alliance's
 * `WIFI:S:<ssid>;T:<type>;P:<password>;;`, and the parts that bite are the ones a hand-written
 * regex gets wrong: fields arrive in any order, and `;` `:` `,` `\` inside an SSID or password are
 * backslash-escaped. A site network called `Level 3; Wing B` is not exotic.
 */
class WifiQrParserTest {

    private fun ok(raw: String) = (WifiQrParser.parse(raw) as WifiScan.Ok).network
    private fun refused(raw: String) = WifiQrParser.parse(raw).also {
        assertTrue("expected a refusal for: $raw", it !is WifiScan.Ok)
    }

    @Test fun a_shared_network_parses() {
        val p = ok("WIFI:S:SiteOffice;T:WPA;P:hunter2;;")
        assertEquals("SiteOffice", p.ssid)
        assertEquals("hunter2", p.password)
        assertEquals(WifiSecurity.WPA, p.security)
        assertEquals(false, p.hidden)
    }

    @Test fun fields_may_arrive_in_any_order() {
        val p = ok("WIFI:P:hunter2;H:true;T:WPA;S:SiteOffice;;")
        assertEquals("SiteOffice", p.ssid)
        assertEquals("hunter2", p.password)
        assertTrue(p.hidden)
    }

    /** Separators inside the value. Rejecting or truncating these is the common bug. */
    @Test fun escaped_separators_survive() {
        val p = ok("""WIFI:S:Level 3\; Wing B;T:WPA;P:a\:b\;c\,d\\e;;""")
        assertEquals("Level 3; Wing B", p.ssid)
        assertEquals("""a:b;c,d\e""", p.password)
    }

    @Test fun an_open_network_has_no_password() {
        val p = ok("WIFI:S:Guest;T:nopass;;")
        assertEquals(WifiSecurity.OPEN, p.security)
        assertNull(p.password)
    }

    /**
     * Plenty of generators omit T. A password means it is protected; without one it is open.
     * Guessing WPA for a passwordless network would produce a suggestion Android rejects.
     */
    @Test fun a_missing_type_is_inferred_from_whether_there_is_a_password() {
        assertEquals(WifiSecurity.WPA, ok("WIFI:S:A;P:pw;;").security)
        assertEquals(WifiSecurity.OPEN, ok("WIFI:S:A;;").security)
    }

    @Test fun wpa3_is_its_own_kind() {
        assertEquals(WifiSecurity.WPA3, ok("WIFI:S:A;T:SAE;P:pw;;").security)
        assertEquals(WifiSecurity.WPA3, ok("WIFI:S:A;T:WPA3;P:pw;;").security)
    }

    /**
     * WEP is carried here so the screen can say WEP rather than "could not read that code".
     * Android's suggestion API has no WEP at all, so the refusal has to name the reason.
     */
    @Test fun wep_is_recognised_rather_than_rejected_as_unreadable() {
        assertEquals(WifiSecurity.WEP, ok("WIFI:S:A;T:WEP;P:pw;;").security)
    }

    @Test fun keys_and_prefix_are_case_insensitive() {
        val p = ok("wifi:s:A;t:wpa;p:pw;;")
        assertEquals("A", p.ssid)
        assertEquals("pw", p.password)
        assertEquals(WifiSecurity.WPA, p.security)
    }

    @Test fun the_trailing_double_semicolon_is_optional() {
        assertEquals("A", ok("WIFI:S:A;T:WPA;P:pw").ssid)
    }

    /**
     * Quotes are part of the name, not a hex marker.
     *
     * An earlier version of this refused `S:"..."` on the theory that it meant a hex-encoded SSID.
     * That reads the zxing wiki backwards — quotes mark a name that merely LOOKS like hex — and
     * Android's own reader (`WifiQrCode.parseZxingWifiQrCode`) has no hex handling at all. Android
     * does not escape `"` when it shares, so a network genuinely called `"Site"` arrives with
     * them, its own scanner joins it, and refusing here would be the only thing that could not.
     */
    @Test fun quotes_are_part_of_the_name() {
        assertEquals(""""Site"""", ok("""WIFI:S:"Site";T:WPA;P:pw;;""").ssid)
    }

    /**
     * `P:ab;cd` from a generator that forgot to escape. Keeping `ab` would save a password that
     * cannot connect, and Settings does not verify a passphrase — the operator would be told
     * "Saved" and simply never get on the network. A stray field with no key is the only evidence
     * that a value was cut, so it is refused.
     */
    @Test fun an_unescaped_separator_inside_a_value_is_refused_not_truncated() {
        refused("WIFI:S:Site;T:WPA;P:ab;cd;;")
    }

    /**
     * WPA2-EAP appears on the zxing wiki and in some generators. A suggestion built from it as a
     * PSK saves and never connects, which is the same silent failure WEP would have had before it
     * was named.
     */
    @Test fun enterprise_is_named_rather_than_saved_as_a_psk() {
        assertEquals(WifiSecurity.ENTERPRISE,
            ok("WIFI:S:A;T:WPA2-EAP;P:pw;;").security)
    }

    /**
     * Android's WifiNetworkSuggestion.Builder throws IllegalArgumentException for these, and the
     * throw happens on a coroutine where it kills the process. A password autocorrected from
     * `O'Brien` to `O’Brien` is an ordinary way to get here.
     */
    @Test fun input_android_cannot_accept_is_refused_here_rather_than_thrown_later() {
        refused("WIFI:S:Site;T:WPA;P:O’Brien2024;;")
        refused("WIFI:S:" + "a".repeat(33) + ";T:WPA;P:pw;;")
        assertEquals(32, ok("WIFI:S:" + "a".repeat(32) + ";T:WPA;P:pw;;").ssid.length)
    }

    @Test fun an_empty_ssid_is_not_a_network() {
        refused("WIFI:S:;T:WPA;P:pw;;")
        refused("WIFI:T:WPA;P:pw;;")
    }

    @Test fun other_codes_are_not_wifi_codes() {
        for (raw in listOf(
            """{"v":2,"c":"abc","env":"prod"}""",   // a FieldSight login QR
            "https://example.com",
            "",
            "WIFI",
            "MECARD:N:Someone;;",
        )) {
            refused(raw)
        }
    }

    /**
     * Four different causes used to share one message telling the operator to scan again. For a
     * password Android cannot accept, scanning again cannot ever work -- so the reason has to
     * reach the screen. This is the same defect as advertising a retry that is deduped away.
     */
    @Test fun a_refusal_says_which_kind_it_is() {
        assertEquals(WifiScan.NotWifi, WifiQrParser.parse("https://example.com"))
        val curly = WifiQrParser.parse("WIFI:S:Site;T:WPA;P:O’Brien2024;;")
        assertTrue(curly is WifiScan.Unusable)
        assertTrue("names the password, not the scan",
            (curly as WifiScan.Unusable).message.contains("password"))
    }

    /**
     * The WFA spec has multi-character keys (PH2, for enterprise). Refusing a whole code because
     * of a field we ignore anyway would say "not a Wi-Fi code" about a Wi-Fi code.
     */
    @Test fun an_unknown_field_is_skipped_not_fatal() {
        assertEquals("A", ok("WIFI:S:A;T:WPA;P:pw;PH2:MSCHAPV2;;").ssid)
    }
}
