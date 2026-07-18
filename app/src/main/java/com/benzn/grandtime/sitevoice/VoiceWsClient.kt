package com.benzn.grandtime.sitevoice

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.benzn.grandtime.core.AppState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Persistent Site-voice WebSocket, held open by CoreService (the FGS). FIRST on-device WS in this
 * repo — device soak required (Task 14). Auth: a fresh Cognito idToken in the handshake
 * `Authorization` header (raw, no Bearer). Reconnect: exponential backoff ([WsBackoff]) on drop,
 * plus an immediate reconnect when the default network comes back. Keepalive: OkHttp pingInterval.
 * Inbound text frames are parsed by [WsMessages] and handed to [onInbound]; [onConnected] fires each
 * time the socket opens (drives offline backfill).
 *
 * Concurrency (single-flight): [scheduleConnect] can be driven from three threads — Main (initial /
 * backoff), the OkHttp reader thread ([onDropped]) and the ConnectivityManager thread
 * ([networkCallback]). To avoid orphan sockets whose stale listener keeps flapping the connected dot
 * and re-firing backfill, every mutable field except [socket] is confined to the [scope] (Main)
 * dispatcher, and each connect attempt carries a monotonically-increasing [generation]. Each attempt
 * builds its OWN listener capturing its `gen`; every callback bails (and cancels its own socket) as
 * soon as `gen != generation`, so at most one attempt — the current generation — ever mutates state
 * or drives reconnect. [socket] alone stays `@Volatile` because [sendVoice] reads it off-Main.
 */
class VoiceWsClient(
    private val wsUrl: String,
    private val tokenProvider: suspend () -> String?,
    private val scope: CoroutineScope,
    private val connectivity: ConnectivityManager,
    private val onInbound: (InboundVoice) -> Unit,
    private val onConnected: () -> Unit,
    private val probe: (String) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // keepalive; also detects half-open sockets
        .build()

    // Only [socket] crosses threads (read off-Main by [sendVoice]); everything below is Main-only.
    @Volatile private var socket: WebSocket? = null
    private var running = false
    private var attempt = 0
    private var connectJob: Job? = null
    // Bumped on every new attempt and on stop(); a listener whose captured gen != this is stale.
    private var generation = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Marshal onto Main before touching any field (this runs on a ConnectivityManager thread).
            scope.launch {
                if (running && socket == null) { attempt = 0; scheduleConnect(immediate = true) }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().build(), networkCallback,
            )
        }
        scheduleConnect(immediate = true)
    }

    fun stop() {
        running = false
        generation++ // invalidate any in-flight attempt / listener still holding an older gen
        connectJob?.cancel(); connectJob = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        socket?.close(1000, "stop"); socket = null
        AppState.siteVoiceConnected.value = false
    }

    /** Enqueue a sendVoice frame. Returns false if the socket isn't currently open. */
    fun sendVoice(siteId: String, s3Key: String, durationS: Int): Boolean {
        val s = socket ?: return false
        return s.send(WsMessages.sendVoiceFrame(siteId, s3Key, durationS))
    }

    private fun scheduleConnect(immediate: Boolean) {
        connectJob?.cancel()
        val gen = ++generation // this attempt's identity; supersedes any earlier in-flight attempt
        connectJob = scope.launch {
            if (!immediate) delay(WsBackoff.delayMillis(attempt))
            if (gen != generation || !running) return@launch
            val token = tokenProvider() ?: run {
                probe("site-voice: no idToken; retry")
                attempt++; scheduleConnect(immediate = false); return@launch
            }
            // tokenProvider() may have suspended; re-check we are still the current attempt.
            if (gen != generation || !running) return@launch
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", token) // raw idToken, no Bearer
                .build()
            // Do NOT assign the returned socket here; onOpen publishes it once actually connected,
            // so socket == null means "not open" (which onAvailable relies on).
            client.newWebSocket(request, buildListener(gen))
        }
    }

    /** A fresh listener per attempt, capturing [gen]. Every callback first checks it is still the
     *  current generation; a superseded attempt cancels its own socket and goes inert. */
    private fun buildListener(gen: Int): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) { webSocket.cancel(); return }
            // Marshal state onto Main and re-check gen there; only the winning generation publishes
            // the socket, so socket only ever holds the current attempt's open socket (or null).
            scope.launch {
                if (gen != generation) { webSocket.cancel(); return@launch }
                socket = webSocket
                attempt = 0
                AppState.siteVoiceConnected.value = true
                probe("site-voice: connected")
                onConnected()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) { webSocket.cancel(); return }
            WsMessages.parseInbound(text)?.let(onInbound)
                ?: probe("site-voice: unparsable frame")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) { webSocket.cancel(); return }
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) { webSocket.cancel(); return }
            onDropped(gen, "closed $code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) { webSocket.cancel(); return }
            onDropped(gen, "failure ${t.message}")
        }
    }

    /** Drop handler for the current attempt [gen]. Marshals onto Main; a superseded gen no-ops so a
     *  single drop can never spawn a second reconnect chain. */
    private fun onDropped(gen: Int, why: String) {
        scope.launch {
            if (gen != generation) return@launch
            socket = null
            AppState.siteVoiceConnected.value = false
            probe("site-voice: dropped ($why)")
            if (running) { attempt++; scheduleConnect(immediate = false) }
        }
    }
}
