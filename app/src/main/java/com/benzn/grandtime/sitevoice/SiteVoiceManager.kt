package com.benzn.grandtime.sitevoice

import android.content.Context
import android.net.ConnectivityManager
import com.benzn.grandtime.ask.AskPlayer
import com.benzn.grandtime.ask.AskRecorder
import com.benzn.grandtime.ask.AskSounds
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.core.AppState
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Orchestrates Site voice: physical PTT key (HoldToTalkKeySource) down/up -> SiteVoiceCore -> executors (AskRecorder,
 * VoiceApiClient, VoiceWsClient, AskPlayer, AskSounds). Sibling to AskManager in CoreService.
 * Reuses AskRecorder (-> AudioRecorder) and AskPlayer; adds the received cue.
 * Mic arbitration reads AppState.captureState (video) + AppState.askActive (Ask); publishes
 * AppState.siteVoiceActive. Offline backfill on (re)connect via LastSeenStore + VoiceApiClient.
 */
class SiteVoiceManager(
    context: Context,
    private val scope: CoroutineScope,
    private val auth: AuthManager,
    apiBaseUrl: String,
    wsUrl: String,
    connectivity: ConnectivityManager,
    private val micHandover: MicHandover,
    private val probe: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val core = SiteVoiceCore()
    private val cacheDir = File(appContext.cacheDir, "sitevoice")
    private val recorder = AskRecorder(appContext, cacheDir)
    private val player = AskPlayer(cacheDir)
    private val sounds = AskSounds(appContext)
    private val api = VoiceApiClient(apiBaseUrl)
    private val lastSeenStore = LastSeenStore(appContext.voiceDataStore)

    /** Reused for every inbound-clip download (single client, not one per download). */
    private val downloadClient = OkHttpClient()

    private var capTimer: Job? = null

    /** The just-stopped outbound clip: set by StopRecording, consumed by UploadAndSend. Stashing it
     *  lets StopRecording release the Site-voice mic BEFORE ReleaseMicToCapture reopens the capture
     *  mic — the two AudioRecords must never hold the physical mic at once (single-capture ROM). */
    private var pendingClip: File? = null

    /** Recently-processed inbound s3Keys, to drop duplicate fanouts and backfill/live overlaps.
     *  Bounded (oldest evicted). Main-confined: only [handleInbound] and [backfill] touch it, both
     *  on [scope]. [markProcessed] returns true the first time a key is seen, false thereafter. */
    private val processedKeys = LinkedHashSet<String>()

    private fun markProcessed(s3Key: String): Boolean {
        if (!processedKeys.add(s3Key)) return false
        if (processedKeys.size > PROCESSED_KEYS_LIMIT) {
            processedKeys.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
        }
        return true
    }

    private val ws = VoiceWsClient(
        wsUrl = wsUrl,
        tokenProvider = { auth.freshIdToken() },
        scope = scope,
        connectivity = connectivity,
        onInbound = { inbound -> scope.launch { handleInbound(inbound) } },
        onConnected = { scope.launch { backfill() } },
        probe = probe,
    )

    private val videoRecording: Boolean
        get() = AppState.captureState.value is CaptureState.RecordingVideo
    private val askActive: Boolean get() = AppState.askActive.value

    fun start() { cacheDir.mkdirs(); ws.start() }

    fun onSosDown() = dispatch { core.onSosDown(videoRecording, askActive) }
    fun onSosUp() = dispatch { core.onSosUp() }

    /** Serialize the SiteVoiceCore mutation AND its command execution on [scope]'s single
     *  dispatcher — mirrors AskManager.dispatch (one talk at a time, no double-send).
     *  Ask<->Site-voice mutual exclusion depends on the acquire-side commands (begin/listening/start)
     *  NOT suspending before [AppState.siteVoiceActive] is published at the end of this launch: if
     *  [MicHandover.begin] ever gains a real suspension point, a concurrent PTT could read a stale
     *  siteVoiceActive=false and slip through the exclusion. Keep begin() non-suspending in practice. */
    private fun dispatch(decide: () -> List<SiteVoiceCommand>) {
        scope.launch {
            execute(decide())
            AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
        }
    }

    private suspend fun execute(commands: List<SiteVoiceCommand>) {
        for (cmd in commands) when (cmd) {
            SiteVoiceCommand.PlayTalkStartCue -> { probe("site-voice: talk"); sounds.listening() }
            SiteVoiceCommand.PlayBusyCue -> { probe("site-voice: busy"); sounds.error() }
            SiteVoiceCommand.PlayErrorCue -> { probe("site-voice: error"); sounds.error() }
            SiteVoiceCommand.AcquireMicFromCapture -> { probe("site-voice: borrow mic"); micHandover.begin() }
            SiteVoiceCommand.ReleaseMicToCapture -> { probe("site-voice: return mic"); micHandover.end() }
            SiteVoiceCommand.StartRecording -> if (!recorder.start()) { fail(); return }
            // Release the Site-voice mic HERE — the FSM orders StopRecording -> ReleaseMicToCapture ->
            // UploadAndSend, so stopping here (not deferring into UploadAndSend) guarantees the mic is
            // freed BEFORE ReleaseMicToCapture reopens the capture mic. Stash the clip for UploadAndSend.
            SiteVoiceCommand.StopRecording -> { pendingClip = recorder.stop() }
            SiteVoiceCommand.ArmCapTimer -> armCap()
            SiteVoiceCommand.CancelCapTimer -> { capTimer?.cancel(); capTimer = null }
            SiteVoiceCommand.UploadAndSend -> uploadAndSend()
            is SiteVoiceCommand.PlayClip -> playClip(cmd.clip)
        }
    }

    private fun armCap() {
        capTimer?.cancel()
        capTimer = scope.launch {
            delay(CAP_MILLIS)
            execute(core.onCapReached())
            AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
        }
    }

    private suspend fun uploadAndSend() {
        val clip = pendingClip
        pendingClip = null
        if (clip == null) { fail(); return }
        val siteId = AppState.selectedSite.value?.id
        val token = auth.freshIdToken()
        if (siteId == null || token == null) { clip.delete(); sendResult(false); return }
        val size = clip.length()
        val durationS = WsMessages.wavDurationSeconds(size)
        val startedAt = Instant.now().toString()
        val result = withContext(Dispatchers.IO) {
            val up = api.uploadUrl(
                token,
                VoiceUploadReq(siteId, clip.name, "audio/wav", startedAt, durationS, size),
            )
            if (up !is VoiceApiClient.UploadUrlResult.Ok) return@withContext false
            if (!api.putFile(up.uploadUrl, "audio/wav", clip)) return@withContext false
            ws.sendVoice(siteId, up.s3Key, durationS)
        }
        clip.delete()
        sendResult(result)
    }

    private suspend fun sendResult(ok: Boolean) {
        execute(core.onSendResult(ok))
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    private suspend fun handleInbound(inbound: InboundVoice) {
        if (!markProcessed(inbound.s3Key)) { probe("site-voice: duplicate ${inbound.s3Key}"); return }
        val clip = download(inbound.s3Key, inbound.senderUserId, inbound.createdAt, inbound.durationS ?: 0)
        if (clip == null) { probe("site-voice: download failed"); return }
        addToInbox(clip)
        inbound.createdAt.takeIf { it.isNotBlank() }?.let { runCatching { lastSeenStore.set(it) } }
        execute(core.onClipReady(clip))
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    /** Backfill = clips missed WHILE OFFLINE, fetched on (re)connect. They populate the inbox (so the
     *  user can see + replay them) but are NOT auto-played — auto-playing a backlog on every reconnect
     *  floods the speaker with old messages. Only LIVE clips ([handleInbound]) auto-play. */
    private suspend fun backfill() {
        val siteId = AppState.selectedSite.value?.id ?: return
        val token = auth.freshIdToken() ?: return
        val since = lastSeenStore.lastSeen.firstOrNull()
        val items = withContext(Dispatchers.IO) { api.backfill(token, siteId, since) }
        var added = 0
        for (it in items.sortedBy { it.createdAt }) {
            if (!markProcessed(it.s3Key)) continue
            val clip = download(it.s3Key, it.senderUserId, it.createdAt, it.durationS ?: 0) ?: continue
            addToInbox(clip)
            it.createdAt.takeIf { c -> c.isNotBlank() }?.let { c -> runCatching { lastSeenStore.set(c) } }
            added++
        }
        if (added > 0) probe("site-voice: backfilled $added to inbox (not auto-played)")
    }

    /** Presign a GET, download the clip bytes to a temp file, wrap in a VoiceClip. */
    private suspend fun download(s3Key: String, sender: String, createdAt: String, durationS: Int): VoiceClip? {
        val token = auth.freshIdToken() ?: return null
        return withContext(Dispatchers.IO) {
            val url = api.downloadUrl(token, s3Key) ?: return@withContext null
            // Counter suffix so concurrent downloads (handleInbound + backfill, both on IO) in the
            // same millisecond never pick the same temp path and clobber each other.
            val file = File(cacheDir, "in_${System.currentTimeMillis()}_${dlCounter.incrementAndGet()}.wav")
            val ok = fetchToFile(url, file)
            if (ok && file.length() > 0) VoiceClip(file, s3Key, sender, createdAt, durationS) else { file.delete(); null }
        }
    }

    /** Raw GET of a presigned S3 URL to [dest] — NO Authorization header (the URL is already
     *  signed). Reuses [downloadClient] rather than constructing a client per call. */
    private fun fetchToFile(url: String, dest: File): Boolean = runCatching {
        downloadClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            dest.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
            true
        }
    }.getOrDefault(false)

    private suspend fun playClip(clip: VoiceClip) {
        val bytes = runCatching { clip.file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) { onPlaybackDone(); return }
        probe("site-voice: playing from ${clip.senderUserId}")
        sounds.received()
        player.play(bytes) { scope.launch { onPlaybackDone() } }
    }

    private suspend fun onPlaybackDone() {
        execute(core.onPlaybackDone())
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    /** Replay a clip already in the inbox (routed through the core so it queues if busy). */
    fun replay(s3Key: String) = dispatch {
        val clip = AppState.siteVoiceInbox.value.firstOrNull { it.s3Key == s3Key }
        if (clip != null) core.onClipReady(clip) else emptyList()
    }

    private fun addToInbox(clip: VoiceClip) {
        val old = AppState.siteVoiceInbox.value
        val updated = (listOf(clip) + old).take(INBOX_LIMIT)
        AppState.siteVoiceInbox.value = updated
        // Delete cache files for clips evicted past INBOX_LIMIT so they don't leak for the process
        // lifetime. Guard: never delete a file still referenced by a kept clip (by s3Key or path).
        val keptKeys = updated.mapTo(HashSet()) { it.s3Key }
        val keptPaths = updated.mapTo(HashSet()) { it.file.path }
        for (evicted in old) {
            if (evicted.s3Key in keptKeys || evicted.file.path in keptPaths) continue
            runCatching { evicted.file.delete() }
        }
    }

    private suspend fun fail() {
        recorder.discard()
        capTimer?.cancel(); capTimer = null
        micHandover.end() // restore video audio if we had borrowed the mic (idempotent no-op otherwise)
        execute(core.onError())
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    fun shutdown() {
        capTimer?.cancel()
        ws.stop()
        recorder.discard()
        player.release()
        sounds.release()
    }

    companion object {
        const val CAP_MILLIS = 15_000L
        const val INBOX_LIMIT = 20
        const val PROCESSED_KEYS_LIMIT = 64

        /** Process-wide monotonic suffix for inbound temp filenames (see [download]). */
        private val dlCounter = AtomicLong(0)
    }
}
