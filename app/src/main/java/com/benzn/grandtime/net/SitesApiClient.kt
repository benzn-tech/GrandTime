package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Injectable GET shim so tests can fake the network call without hitting OkHttp/Android stubs.
 * Real path is [RealSitesHttp] (OkHttp); tests inject a fake implementation.
 */
interface SitesHttpFns {
    fun getJson(url: String, authToken: String): HttpResult
}

/** Real OkHttp-backed implementation of [SitesHttpFns]. Not unit-tested (verified on-device). */
class RealSitesHttp : SitesHttpFns {
    override fun getJson(url: String, authToken: String): HttpResult {
        val req = Request.Builder().url(url)
            .header("Authorization", authToken)
            .apply { attachDeviceHeaders(this) }
            .get()
            .build()
        OK_HTTP.newCall(req).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    companion object {
        private val OK_HTTP = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * HTTP client for the FieldSight org sites endpoint (SP4b, sites the user may pick as work
 * site). `http` mirrors RecordingsApiClient's testability pattern — tests inject a fake; the
 * pure JSON parsing (parseSites) is TDD-covered here.
 */
class SitesApiClient(
    private val baseUrl: String,
    private val http: SitesHttpFns = RealSitesHttp(),
) {
    data class SiteOption(val id: String, val slug: String, val name: String, val address: String? = null)

    /**
     * The account's sites, or NULL when the request did not produce an answer.
     *
     * [listSites] returns an empty list for a network error, a non-2xx response and a malformed
     * body alike, so a caller cannot tell "this account has no sites" from "the device is offline".
     * That was harmless while the only caller displayed the list. It is not harmless for a caller
     * that ACTS on emptiness: clearing a selection because an account has no sites would clear a
     * perfectly good one every time the device started without a connection.
     */
    fun fetchSites(idToken: String): List<SiteOption>? {
        val result = runCatching { http.getJson("$baseUrl/org/sites", idToken) }.getOrElse { return null }
        return parseSitesOrNull(result)
    }

    fun listSites(idToken: String): List<SiteOption> {
        val result = runCatching { http.getJson("$baseUrl/org/sites", idToken) }
            .getOrElse { return emptyList() }
        return parseSites(result)
    }

    companion object {
        /**
         * Sites from a successful response, or NULL when there is no trustworthy answer: a non-2xx,
         * a body that is not JSON, or JSON without a `sites` array. An empty `sites` array is a
         * real answer -- the account has none -- and is returned as an empty list.
         */
        fun parseSitesOrNull(r: HttpResult): List<SiteOption>? {
            if (r.code !in 200..299) return null
            return runCatching {
                val arr = JSONObject(r.body).optJSONArray("sites") ?: return null
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SiteOption(
                        id = id,
                        slug = o.optString("slug"),
                        name = o.optString("name"),
                        address = o.optString("address").takeIf { it.isNotBlank() },
                    )
                }
            }.getOrNull()
        }

        fun parseSites(r: HttpResult): List<SiteOption> {
            if (r.code !in 200..299) return emptyList()
            return runCatching {
                val json = JSONObject(r.body)
                val arr = json.optJSONArray("sites") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SiteOption(
                        id = id,
                        slug = o.optString("slug"),
                        name = o.optString("name"),
                        address = o.optString("address").takeIf { it.isNotBlank() },
                    )
                }
            }.getOrElse { emptyList() }
        }
    }
}
