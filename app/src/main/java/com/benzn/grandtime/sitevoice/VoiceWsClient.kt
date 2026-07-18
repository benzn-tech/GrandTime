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

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var attempt = 0
    private var connectJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (running && socket == null) { attempt = 0; scheduleConnect(immediate = true) }
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
        connectJob = scope.launch {
            if (!immediate) delay(WsBackoff.delayMillis(attempt))
            if (!running) return@launch
            val token = tokenProvider() ?: run {
                probe("site-voice: no idToken; retry")
                attempt++; scheduleConnect(immediate = false); return@launch
            }
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", token) // raw idToken, no Bearer
                .build()
            socket = client.newWebSocket(request, listener)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            AppState.siteVoiceConnected.value = true
            probe("site-voice: connected")
            onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            WsMessages.parseInbound(text)?.let(onInbound)
                ?: probe("site-voice: unparsable frame")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDropped("closed $code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onDropped("failure ${t.message}")
        }

        private fun onDropped(why: String) {
            socket = null
            AppState.siteVoiceConnected.value = false
            probe("site-voice: dropped ($why)")
            if (running) { attempt++; scheduleConnect(immediate = false) }
        }
    }
}
