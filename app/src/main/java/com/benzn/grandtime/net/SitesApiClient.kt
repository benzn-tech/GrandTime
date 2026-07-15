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

    fun listSites(idToken: String): List<SiteOption> {
        val result = runCatching { http.getJson("$baseUrl/org/sites", idToken) }
            .getOrElse { return emptyList() }
        return parseSites(result)
    }

    companion object {
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
