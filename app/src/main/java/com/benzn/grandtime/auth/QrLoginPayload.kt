package com.benzn.grandtime.auth

import org.json.JSONObject

/** Payload carried by a FieldSight login QR: {"v":2,"c":<one-time code>,"env":"prod"}. */
data class QrLoginPayload(val code: String, val env: String)

object QrLoginParser {
    /** Returns null for anything that isn't a valid v2 FieldSight login QR (wrong shape, other QRs). */
    fun parse(raw: String): QrLoginPayload? = runCatching {
        val o = JSONObject(raw)
        if (o.optInt("v", -1) != 2) return null
        val c = o.optString("c").takeIf { it.isNotBlank() } ?: return null
        val env = o.optString("env").takeIf { it.isNotBlank() } ?: return null
        QrLoginPayload(c, env)
    }.getOrNull()
}
