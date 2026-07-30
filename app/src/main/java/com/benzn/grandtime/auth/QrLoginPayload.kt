package com.benzn.grandtime.auth

import org.json.JSONObject

/** Payload carried by a FieldSight login QR: {"v":1,"u":<email>,"c":<one-time code>,"env":"prod"}. */
data class QrLoginPayload(val username: String, val code: String, val env: String)

object QrLoginParser {
    /** Returns null for anything that isn't a valid v1 FieldSight login QR (wrong shape, other QRs). */
    fun parse(raw: String): QrLoginPayload? = runCatching {
        val o = JSONObject(raw)
        if (o.optInt("v", -1) != 1) return null
        val u = o.optString("u").takeIf { it.isNotBlank() } ?: return null
        val c = o.optString("c").takeIf { it.isNotBlank() } ?: return null
        val env = o.optString("env").takeIf { it.isNotBlank() } ?: return null
        QrLoginPayload(u, c, env)
    }.getOrNull()
}
