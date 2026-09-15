package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import org.json.JSONObject

/** The newest published build for this stage, as `GET /org/app/latest` describes it. */
data class LatestRelease(
    val versionCode: Long,
    val versionName: String,
    val minVersionCode: Long,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    /** Presigned and short-lived; fetched without the Authorization header. */
    val url: String,
)

/**
 * Asks org-api whether a newer build of this app has been published.
 *
 * Signed-in only, by the owner's choice: the download link is never handed to a device nobody has
 * signed in on. A device on a stage with no release yet hears [Answer.NoRelease]; anything else
 * that is not a complete, well-formed release is [Answer.Failed], which is retried later and never
 * read as "up to date" -- the two must not collapse into one, or a broken endpoint would quietly
 * stop every device from ever updating.
 */
class AppUpdateClient(
    private val baseUrl: String,
    private val http: SitesHttpFns = RealSitesHttp(),
) {
    sealed interface Answer {
        data object NoRelease : Answer
        data class Release(val release: LatestRelease) : Answer
        data object Failed : Answer
    }

    fun latest(idToken: String): Answer = runCatching {
        parse(http.getJson("$baseUrl/org/app/latest", idToken))
    }.getOrDefault(Answer.Failed)

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun parse(r: HttpResult): Answer {
            if (r.code !in 200..299) return Answer.Failed
            val json = runCatching { JSONObject(r.body) }.getOrNull() ?: return Answer.Failed
            if (!json.has("available")) return Answer.Failed
            if (!json.optBoolean("available", false)) return Answer.NoRelease
            return runCatching {
                val release = LatestRelease(
                    versionCode = json.getLong("versionCode"),
                    versionName = json.getString("versionName"),
                    minVersionCode = json.optLong("minVersionCode", 0),
                    sha256 = json.getString("sha256").lowercase(),
                    sizeBytes = json.getLong("sizeBytes"),
                    notes = json.optString("notes", ""),
                    url = json.getString("url"),
                )
                require(release.versionCode > 0 && release.sizeBytes > 0)
                require(release.minVersionCode in 0..release.versionCode)
                require(SHA256.matches(release.sha256))
                require(release.url.startsWith("https://"))
                Answer.Release(release)
            }.getOrDefault(Answer.Failed)
        }
    }
}
