package com.benzn.grandtime.upload

/** Video waits for Wi-Fi only when the user enabled it; audio/photo always upload on any network. */
fun uploadRequiresUnmetered(kind: String, videoUploadWifiOnly: Boolean): Boolean =
    kind == "video" && videoUploadWifiOnly
