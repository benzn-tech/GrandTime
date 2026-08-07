package com.benzn.grandtime.upload

import com.benzn.grandtime.net.RecordingsApiClient
import com.benzn.grandtime.net.RecordingsApiClient.UploadUrlResult

/**
 * Who can fix this failure.
 *
 * Classifying by responsibility rather than by HTTP status is deliberate: status tables
 * change, responsibility does not. Every remedy in the upload path hangs off this answer,
 * and only one of the four is worth retrying blindly.
 */
enum class FailureClass {
    /** The world is temporarily unhelpful. Back off and try again. */
    TRANSIENT,

    /** Someone at the site can change the physical situation — sign in, find Wi-Fi. */
    SITE_FIXABLE,

    /**
     * Only a change on our side can make this succeed. Retrying a mis-mapped identity does
     * not fix a mis-mapped identity; before this class existed, such records were retried
     * for seven days and then dropped without telling anyone.
     */
    OPERATOR_FIXABLE,

    /** Cannot ever succeed. */
    DEAD,
}

/** A classified failure. [code] is the fingerprint the device ledger groups on. */
data class UploadFailure(val cls: FailureClass, val code: String?)

object UploadFailures {
    val NEEDS_LOGIN = UploadFailure(FailureClass.SITE_FIXABLE, "needs_login")
    val FILE_MISSING = UploadFailure(FailureClass.DEAD, "file_missing")
    val AGED_OUT = UploadFailure(FailureClass.DEAD, "aged_out")
    val EXCEPTION = UploadFailure(FailureClass.TRANSIENT, null)

    private val TRANSIENT = UploadFailure(FailureClass.TRANSIENT, null)

    /** null = not a failure. */
    fun ofUploadUrl(r: UploadUrlResult): UploadFailure? = when (r) {
        is UploadUrlResult.Ok -> null
        is UploadUrlResult.Busy -> TRANSIENT
        // Reached only after freshIdToken() returned a token, so the token is good and the
        // server rejected the identity. Waiting has never fixed that.
        is UploadUrlResult.AuthExpired -> UploadFailure(FailureClass.OPERATOR_FIXABLE, "uploadurl_401")
        // An Error always means the server answered and the answer was wrong — a total
        // absence of response is Busy. So it is ours by default, not the world's.
        is UploadUrlResult.Error ->
            if (r.code == 0) UploadFailure(FailureClass.OPERATOR_FIXABLE, "uploadurl_malformed")
            else UploadFailure(FailureClass.OPERATOR_FIXABLE, "uploadurl_${r.code}")
    }

    /** null = not a failure. */
    fun ofComplete(status: Int): UploadFailure? = when {
        status in 200..299 -> null
        RecordingsApiClient.isTransient(status) -> TRANSIENT
        else -> UploadFailure(FailureClass.OPERATOR_FIXABLE, "complete_$status")
    }
}
