package com.benzn.grandtime.db

/**
 * The statuses that mean "recorded here, not yet on the server".
 *
 * Mirrors the hardcoded list inside `countOrphanedPending` / `countPendingForAuthor`. It is
 * a constant so that widening the SQL and forgetting the fakes cannot leave the ownership
 * contract test asserting an obsolete contract while staying green.
 *
 * `dead` is deliberately absent: a dead record is not unsent work about to be lost, it is
 * already lost, and counting it in the sign-out warning would make that warning
 * un-clearable.
 */
internal val UNSENT_STATUSES = setOf("pending", "failed", "uploading", "retrying", "frozen")
