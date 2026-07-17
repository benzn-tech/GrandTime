package com.benzn.grandtime.ui

/** Confirmation body for deleting a recording — warns harder when it hasn't safely uploaded yet. */
fun deleteConfirmMessage(uploadStatus: String): String =
    if (uploadStatus == "uploaded")
        "Delete this recording from the device? It stays on the server."
    else
        "This recording hasn't finished uploading. Delete it anyway? It will be lost."
