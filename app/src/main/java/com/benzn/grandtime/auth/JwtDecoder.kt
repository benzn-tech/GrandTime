package com.benzn.grandtime.auth

import java.util.Base64

data class JwtClaims(val sub: String, val email: String?, val name: String?)

/** 解 idToken payload 段(不验签——签名由 Cognito 颁发时保证;本地只读 claim)。纯 JVM。 */
object JwtDecoder {
    fun decode(idToken: String): JwtClaims? {
        return try {
            val parts = idToken.split(".")
            if (parts.size < 2) return null
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))

            // Extract fields using regex
            val subPattern = """"sub"\s*:\s*"([^"]*)"""".toRegex()
            val emailPattern = """"email"\s*:\s*"([^"]*)"""".toRegex()
            val namePattern = """"name"\s*:\s*"([^"]*)"""".toRegex()
            val cognitoPattern = """"cognito:username"\s*:\s*"([^"]*)"""".toRegex()

            val sub = subPattern.find(payload)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
            val email = emailPattern.find(payload)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val name = (namePattern.find(payload)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: cognitoPattern.find(payload)?.groupValues?.get(1)?.takeIf { it.isNotBlank() })

            JwtClaims(sub = sub, email = email, name = name)
        } catch (e: Exception) {
            null
        }
    }
}
