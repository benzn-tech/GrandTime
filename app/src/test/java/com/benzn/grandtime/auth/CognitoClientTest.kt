package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitoClientTest {

    @Test fun `success maps AuthenticationResult to Tokens`() {
        val body = """{"AuthenticationResult":{"IdToken":"idt","AccessToken":"act","RefreshToken":"rft"}}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(200, body))
        assertEquals(AuthOutcome.Tokens("idt", "rft"), r)
    }

    @Test fun `refresh success without RefreshToken keeps null-safe (id only)`() {
        val body = """{"AuthenticationResult":{"IdToken":"idt2","AccessToken":"act2"}}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(200, body))
        assertEquals(AuthOutcome.Tokens("idt2", null), r)
    }

    @Test fun `NEW_PASSWORD_REQUIRED challenge maps to NewPasswordRequired`() {
        val body = """{"ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"sess"}"""
        assertEquals(AuthOutcome.NewPasswordRequired, CognitoClient.parseInitiateAuth(HttpResult(200, body)))
    }

    @Test fun `NotAuthorized maps to friendly error`() {
        val body = """{"__type":"NotAuthorizedException","message":"Incorrect username or password."}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(400, body)) as AuthOutcome.Error
        assertEquals("Incorrect email or password", r.message)
    }

    @Test fun `UserNotFound also maps to same friendly error (no account enumeration)`() {
        val body = """{"__type":"UserNotFoundException","message":"User does not exist."}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(400, body)) as AuthOutcome.Error
        assertEquals("Incorrect email or password", r.message)
    }

    @Test fun `password reset required guides to web`() {
        val body = """{"__type":"PasswordResetRequiredException"}"""
        val r = CognitoClient.parseInitiateAuth(HttpResult(400, body)) as AuthOutcome.Error
        assertTrue(r.message.contains("web app"))
    }

    @Test fun `unknown error falls back to generic`() {
        val r = CognitoClient.parseInitiateAuth(HttpResult(500, "boom")) as AuthOutcome.Error
        assertTrue(r.message.isNotBlank())
    }

    @Test fun `signIn uses injected http and maps success to Tokens`() {
        val fake: (String, String) -> HttpResult = { _, _ ->
            HttpResult(200, """{"AuthenticationResult":{"IdToken":"idX","RefreshToken":"rtX"}}""")
        }
        val client = CognitoClient("clientId", "ap-southeast-2", fake)
        assertEquals(AuthOutcome.Tokens("idX", "rtX"), client.signIn("u", "p"))
    }

    @Test fun `refresh NotAuthorized maps to AuthInvalid not Error`() {
        val fake: (String, String) -> HttpResult = { _, _ ->
            HttpResult(400, """{"__type":"NotAuthorizedException","message":"Refresh Token has expired."}""")
        }
        val client = CognitoClient("clientId", "ap-southeast-2", fake)
        assertEquals(AuthOutcome.AuthInvalid, client.refresh("stale-refresh-token"))
    }

    @Test fun `refresh network exception maps to Error not AuthInvalid`() {
        val fake: (String, String) -> HttpResult = { _, _ -> throw java.io.IOException("down") }
        val client = CognitoClient("clientId", "ap-southeast-2", fake)
        val r = client.refresh("any-refresh-token") as AuthOutcome.Error
        assertEquals("Network error — check your connection", r.message)
    }

    @Test fun `refresh 5xx gateway error stays Error not AuthInvalid`() {
        val fake: (String, String) -> HttpResult = { _, _ -> HttpResult(500, "gateway boom") }
        val client = CognitoClient("clientId", "ap-southeast-2", fake)
        val r = client.refresh("any-refresh-token")
        assertTrue(r is AuthOutcome.Error)
    }

    @Test fun `refresh success maps AuthenticationResult to Tokens`() {
        val fake: (String, String) -> HttpResult = { _, _ ->
            HttpResult(200, """{"AuthenticationResult":{"IdToken":"idX","RefreshToken":"rtX"}}""")
        }
        val client = CognitoClient("clientId", "ap-southeast-2", fake)
        assertEquals(AuthOutcome.Tokens("idX", "rtX"), client.refresh("valid-refresh-token"))
    }
}
