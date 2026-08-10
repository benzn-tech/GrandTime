package com.benzn.grandtime.upload

import com.benzn.grandtime.net.RecordingsApiClient.UploadUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadFailureTest {

    @Test fun `ok is not a failure`() {
        assertNull(UploadFailures.ofUploadUrl(UploadUrlResult.Ok("r", "https://u", "k")))
    }

    @Test fun `busy is the world being unhelpful, not us`() {
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofUploadUrl(UploadUrlResult.Busy(503))!!.cls)
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofUploadUrl(UploadUrlResult.Busy(0))!!.cls)
    }

    // The pair the old code conflated: a 401 reached only after freshIdToken() handed us a
    // token means the token is good and the SERVER rejected the identity. Waiting cannot fix it.
    @Test fun `a 401 on a fresh token is ours to fix`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.AuthExpired)!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_401", f.code)
    }

    @Test fun `a dead session is the site's to fix, by signing in`() {
        assertEquals(FailureClass.SITE_FIXABLE, UploadFailures.NEEDS_LOGIN.cls)
        assertEquals("needs_login", UploadFailures.NEEDS_LOGIN.code)
    }

    @Test fun `403 from upload-url is ours`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.Error(403, "HTTP 403: denied"))!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_403", f.code)
    }

    @Test fun `any other real code from upload-url is ours, and keeps its number`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.Error(404, "HTTP 404"))!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_404", f.code)
    }

    @Test fun `an unusable 2xx has its own fingerprint`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.Error(0, "malformed response"))!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_malformed", f.code)
    }

    @Test fun `complete 2xx is not a failure`() {
        assertNull(UploadFailures.ofComplete(200))
        assertNull(UploadFailures.ofComplete(204))
    }

    @Test fun `complete transient stays transient`() {
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofComplete(503)!!.cls)
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofComplete(429)!!.cls)
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofComplete(0)!!.cls)
    }

    // complete 403 is the mis-scoped-identity case this whole design exists for.
    @Test fun `complete 401 and 403 are ours, and are told apart`() {
        assertEquals("complete_401", UploadFailures.ofComplete(401)!!.code)
        assertEquals("complete_403", UploadFailures.ofComplete(403)!!.code)
        assertEquals(FailureClass.OPERATOR_FIXABLE, UploadFailures.ofComplete(403)!!.cls)
    }

    // The backend answers 409 when the object is not in S3 — the one 4xx that says
    // "the bytes never arrived, send them again". Freezing it strands exactly the
    // recording the check exists to save.
    @Test fun `complete 409 means re-send the bytes, so it is retryable`() {
        val f = UploadFailures.ofComplete(409)!!
        assertEquals(FailureClass.TRANSIENT, f.cls)
        assertEquals("complete_409", f.code)
    }

    @Test fun `complete other 4xx is ours, and keeps its number`() {
        val f = UploadFailures.ofComplete(422)!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("complete_422", f.code)
    }

    @Test fun `a vanished file and an aged-out record are dead`() {
        assertEquals(FailureClass.DEAD, UploadFailures.FILE_MISSING.cls)
        assertEquals(FailureClass.DEAD, UploadFailures.AGED_OUT.cls)
    }

    @Test fun `an exception is the world, not the request`() {
        assertEquals(FailureClass.TRANSIENT, UploadFailures.EXCEPTION.cls)
    }
}
