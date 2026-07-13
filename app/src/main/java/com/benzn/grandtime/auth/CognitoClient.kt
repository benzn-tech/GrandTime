package com.benzn.grandtime.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String)

sealed interface AuthOutcome {
    data class Tokens(val idToken: String, val refreshToken: String?) : AuthOutcome
    data object NewPasswordRequired : AuthOutcome
    data class Error(val message: String) : AuthOutcome
}

/**
 * 裸 HTTP InitiateAuth,对齐 web cognito.js。http 可注入便于单测;默认走 OkHttp。
 */
class CognitoClient(
    private val clientId: String,
    private val region: String,
    http: ((target: String, body: String) -> HttpResult)? = null,
) {
    // Falls back to the real OkHttp path when no override is injected (tests inject a fake).
    private val http: (String, String) -> HttpResult = http ?: ::defaultHttp

    fun signIn(username: String, password: String): AuthOutcome {
        val body = JSONObject()
            .put("AuthFlow", "USER_PASSWORD_AUTH")
            .put("ClientId", clientId)
            .put("AuthParameters", JSONObject().put("USERNAME", username).put("PASSWORD", password))
            .toString()
        return runCatching { parseInitiateAuth(http("InitiateAuth", body)) }
            .getOrElse { AuthOutcome.Error("Network error — check your connection") }
    }

    fun refresh(refreshToken: String): AuthOutcome {
        val body = JSONObject()
            .put("AuthFlow", "REFRESH_TOKEN_AUTH")
            .put("ClientId", clientId)
            .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", refreshToken))
            .toString()
        return runCatching { parseInitiateAuth(http("InitiateAuth", body)) }
            .getOrElse { AuthOutcome.Error("Network error — check your connection") }
    }

    private fun defaultHttp(target: String, body: String): HttpResult {
        val endpoint = "https://cognito-idp.$region.amazonaws.com/"
        val req = Request.Builder().url(endpoint)
            .header("Content-Type", "application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.$target")
            .post(body.toRequestBody("application/x-amz-json-1.1".toMediaType()))
            .build()
        OK_HTTP.newCall(req).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    companion object {
        private val OK_HTTP = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        fun parseInitiateAuth(result: HttpResult): AuthOutcome {
            val json = runCatching { JSONObject(result.body) }.getOrNull()
                ?: return AuthOutcome.Error("Network error — check your connection")
            json.optJSONObject("AuthenticationResult")?.let { ar ->
                val id = ar.optString("IdToken").takeIf { it.isNotBlank() }
                    ?: return AuthOutcome.Error("Login failed — please try again")
                return AuthOutcome.Tokens(id, ar.optString("RefreshToken").takeIf { it.isNotBlank() })
            }
            if (json.optString("ChallengeName") == "NEW_PASSWORD_REQUIRED") {
                return AuthOutcome.NewPasswordRequired
            }
            return AuthOutcome.Error(errorMessageFor(json.optString("__type")))
        }

        fun errorMessageFor(type: String): String = when {
            type.contains("NotAuthorized") || type.contains("UserNotFound") -> "Incorrect email or password"
            type.contains("PasswordResetRequired") -> "Password reset required — use the web app"
            type.contains("UserNotConfirmed") -> "Account not confirmed — use the web app"
            type.contains("TooManyRequests") -> "Too many attempts — try again later"
            else -> "Login failed — please try again"
        }
    }
}
