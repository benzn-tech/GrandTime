package com.benzn.grandtime.capture

import android.content.Context
import android.media.MediaActionSound
import android.media.MediaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Recording, photo and low-battery sounds (spec §2.7).
 *
 * Every event has its own clip in res/raw, recorded for the product (2026-09-15) and replacing the
 * system tones plus the two shared "recording started / stopped" voice lines. Video and audio each
 * have their own start, pause and stop: the device is worn on a chest harness with the screen off,
 * confusing start with stop is the most expensive mistake this product allows -- you believe you
 * are recording and you are not -- and not knowing WHICH kind is running is the next one.
 *
 * Clips are looked up by NAME at runtime. A missing clip falls back to the system tone rather than
 * to silence, so a renamed file degrades the wording, never the signal. CaptureSoundsResourcesTest
 * pins that every name used here is actually bundled.
 *
 * Levels: the recorded set was about 12 dB quieter than the lines it replaces (peaks near -14 dBFS
 * against -1.5), so every clip was raised to a -1.5 dBFS peak on import. Gain only; the sounds are
 * otherwise as delivered.
 */
class CaptureSounds(private val context: Context? = null) {

    enum class Media(internal val prefix: String) { VIDEO("video"), AUDIO("audio") }

    private val tones = MediaActionSound().apply {
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
        load(MediaActionSound.SHUTTER_CLICK)
    }

    /** The start cue currently being awaited, so a cancelled wait can stop it. */
    private var announcer: MediaPlayer? = null

    /**
     * Fire-and-forget cues still playing. A set, not one slot: a photo taken while a stop cue plays,
     * or a battery warning during either, must not cut the other off.
     */
    private val oneShots = mutableSetOf<MediaPlayer>()

    /**
     * Play the start cue -- or the resume cue, with [resumed] -- and DO NOT RETURN until it has
     * finished.
     *
     * The caller starts the recorder after this, so the cue is over before the microphone is live.
     * Otherwise the device narrates into its own recording: the transcriber hears the cue in a voice
     * that belongs to nobody in the room, and diarisation gains a speaker who is not a person.
     *
     * A resume looks for `<media>_resumed` first and falls back to `<media>_started`, so a resume
     * clip dropped into res/raw is used without a code change.
     *
     * ALWAYS returns, and that matters more than the silence it buys. If playback fails, hangs, or
     * the clip is missing, this gives up and lets the recording start: losing a whole session to
     * save a second of noise is not a trade worth making.
     */
    suspend fun startRecordingAndAwait(media: Media, resumed: Boolean = false) {
        val names = buildList {
            if (resumed) add("${media.prefix}_resumed")
            add("${media.prefix}_started")
        }
        withTimeoutOrNull(ANNOUNCE_TIMEOUT_MS) { playAndAwait(names) }
    }

    /** Pause, not stop: the session stays open, and the clip says so. */
    fun pauseRecording(media: Media) = play("${media.prefix}_paused", MediaActionSound.STOP_VIDEO_RECORDING)

    fun stopRecording(media: Media) = play("${media.prefix}_stopped", MediaActionSound.STOP_VIDEO_RECORDING)

    fun shutter() = play(PHOTO, MediaActionSound.SHUTTER_CLICK)

    /** No system tone means "battery"; a missing clip plays nothing and the home banner remains. */
    fun lowBattery() = play(LOW_BATTERY, fallbackTone = null)

    private fun rawId(name: String): Int {
        val ctx = context ?: return 0
        return ctx.resources.getIdentifier(name, "raw", ctx.packageName)
    }

    private suspend fun playAndAwait(names: List<String>) {
        val ctx = context
        val id = names.map(::rawId).firstOrNull { it != 0 } ?: 0
        if (ctx == null || id == 0) {
            // Not bundled: the tone is the whole signal, so only wait it out.
            tones.play(MediaActionSound.START_VIDEO_RECORDING)
            delay(TONE_TAIL_MS)
            return
        }
        suspendCancellableCoroutine<Unit> { cont ->
            val ok = runCatching {
                releaseAnnouncer()
                val p = MediaPlayer.create(ctx, id) ?: return@runCatching false
                p.setOnCompletionListener {
                    releaseAnnouncer()
                    if (cont.isActive) cont.resume(Unit)
                }
                p.setOnErrorListener { _, _, _ ->
                    releaseAnnouncer()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                announcer = p
                p.start()
                true
            }.getOrDefault(false)
            if (!ok && cont.isActive) cont.resume(Unit)
            cont.invokeOnCancellation { releaseAnnouncer() }
        }
    }

    // MediaPlayer.create() with no AudioAttributes, on purpose. The replaced code set
    // USAGE_ASSISTANCE_SONIFICATION after create(), which has no effect once the player is prepared,
    // so every prompt that has been heard in the field played on the media stream. Applying the
    // attribute for real would move these onto the system stream, whose volume on this ROM has
    // never been checked.
    private fun play(name: String, fallbackTone: Int?) {
        val ctx = context
        val id = rawId(name)
        if (ctx == null || id == 0) {
            fallbackTone?.let { tones.play(it) }
            return
        }
        runCatching {
            val p = MediaPlayer.create(ctx, id)
            if (p == null) {
                fallbackTone?.let { tones.play(it) }
                return
            }
            synchronized(oneShots) { oneShots += p }
            val done = {
                synchronized(oneShots) { oneShots -= p }
                p.release()
            }
            p.setOnCompletionListener { done() }
            p.setOnErrorListener { _, _, _ -> done(); true }
            p.start()
        }
    }

    private fun releaseAnnouncer() {
        announcer?.runCatching { stop() }
        announcer?.release()
        announcer = null
    }

    fun release() {
        releaseAnnouncer()
        synchronized(oneShots) {
            oneShots.forEach { it.runCatching { release() } }
            oneShots.clear()
        }
        tones.release()
    }

    companion object {
        /** Upper bound on waiting for a start cue. The longest is video_started at 1.57 s; anything
         *  longer than this is a stuck player, not a cue. */
        const val ANNOUNCE_TIMEOUT_MS = 3_000L
        /** The system tone is short; long enough that it is over, short enough that a build without
         *  the clip still starts recording promptly. */
        const val TONE_TAIL_MS = 350L
        const val PHOTO = "photo_captured"
        const val LOW_BATTERY = "low_battery"
        /** Every event suffix a [Media] prefix is combined with. `resumed` is optional by design. */
        val MEDIA_EVENTS = listOf("started", "paused", "stopped")
    }
}
