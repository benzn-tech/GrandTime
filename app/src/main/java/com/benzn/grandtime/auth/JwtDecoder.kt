package com.benzn.grandtime.auth

import java.util.Base64
import org.json.JSONObject

data class JwtClaims(val sub: String, val email: String?, val name: String?)

/** 解 idToken payload 段(不验签——签名由 Cognito 颁发时保证;本地只读 claim)。纯 JVM。 */
object JwtDecoder {
    fun decode(idToken: String): JwtClaims? {
        return try {
            val parts = idToken.split(".")
            if (parts.size < 2) return null
            val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
            val payload = String(payloadBytes, Charsets.UTF_8)

            val obj = JSONObject(payload)

            val sub = obj.optString("sub", "")
            if (sub.isBlank()) return null

            val email = obj.optString("email", null)
            val name = obj.optString("name", null) ?: obj.optString("cognito:username", null)

            JwtClaims(sub = sub, email = email, name = name)
        } catch (e: Exception) {
            null
        }
    }
}
