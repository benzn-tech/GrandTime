package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord

/**
 * How long a recording has been *trying*, as opposed to how long it has existed.
 *
 * The give-up rule is age-based rather than attempt-based because an attempt count charges
 * a server outage to the recording. Freezing introduces the same hazard one level up: a
 * record frozen because only we can fix it is not failing to upload, it is waiting for us —
 * so the wait must not be billed to it. Frozen time is subtracted, and banked on thaw, so a
 * fix that lands on day nine still finds a record with budget left.
 *
 * Without the credit the mechanism inverts: a record frozen on day 2 and thawed on day 9
 * arrives at the age check with its class already cleared and dies without a single retry,
 * which is precisely the silent loss the freeze exists to prevent.
 */
object UploadAging {

    fun effectiveAgeMs(record: CaptureRecord, now: Long): Long {
        val frozenNow = record.frozenSinceMs?.let { now - it } ?: 0L
        return now - record.startedAt - record.frozenCreditMs - frozenNow
    }

    fun shouldGiveUp(record: CaptureRecord, now: Long): Boolean {
        // A frozen record is waiting on us, not failing. It has no deadline.
        if (record.frozenSinceMs != null) return false
        // Belt and braces for the case that motivated this file: a record that has ever been
        // frozen is owed one attempt after its thaw before any deadline applies again.
        if (record.frozenCreditMs > 0 && (record.lastAttemptAt ?: 0) < now - record.frozenCreditMs) {
            return false
        }
        return effectiveAgeMs(record, now) > GIVE_UP_AFTER_MS
    }

    fun creditOnThaw(record: CaptureRecord, now: Long): Long =
        record.frozenCreditMs + (record.frozenSinceMs?.let { now - it } ?: 0L)
}
