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
}
