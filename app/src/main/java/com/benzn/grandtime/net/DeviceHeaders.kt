package com.benzn.grandtime.net

/**
 * The device ledger's heartbeat rides these headers on every org-api request.
 *
 * Attached at the request builders, beside Authorization — deliberately NOT via
 * an OkHttp interceptor. [RecordingsApiClient] derives its S3 upload client from
 * the org-api one (`UPLOAD_HTTP = OK_HTTP.newBuilder()`), so an interceptor on
 * the shared builder is inherited by the client that PUTs media to S3 presigned
 * URLs. That is needless risk against a signed request and hands device identity
 * to a service with no use for it. Attaching here cannot reach S3 by
 * construction.
 *
 * An absent tag is OMITTED rather than sent empty: the backend turns a
 * uuid-without-tag into an `unclaimed:` row so a human can claim it, and an
 * empty string would defeat that.
 */
fun deviceHeaders(assetTag: String?, uuid: String, appVersion: String): List<Pair<String, String>> {
    if (uuid.isBlank()) return emptyList()
    return buildList {
        assetTag?.takeIf { it.isNotBlank() }?.let { add("X-Device-Tag" to it) }
        add("X-Device-Id" to uuid)
        if (appVersion.isNotBlank()) add("X-App-Version" to appVersion)
    }
}

/**
 * Attach the live device identity to an org-api request under construction.
 *
 * ONLY call this on requests to org-api. Never on the S3 presigned-URL PUT —
 * see the note above.
 */
fun attachDeviceHeaders(builder: okhttp3.Request.Builder) {
    deviceHeaders(
        com.benzn.grandtime.device.DeviceIdentity.assetTag(),
        com.benzn.grandtime.device.DeviceIdentity.deviceUuid(),
        com.benzn.grandtime.BuildConfig.VERSION_NAME,
    ).forEach { (name, value) -> builder.header(name, value) }
}
