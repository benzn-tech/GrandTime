package com.benzn.grandtime.ask

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Ask start cue (ask_agent, 1.3 s) must finish before the microphone opens, must be able to
 * give up, and a cue that outlived its own ask must not open the microphone afterwards.
 *
 * Source-level: SoundPool, MediaPlayer and the recorder do not exist on the JVM. The command ORDER
 * (cue, then StartRecording) is already pinned in AskCoreTest; this pins that the executor waits
 * between the two and re-checks the state after waiting.
 */
class AskCueBeforeMicTest {

    // Comments stripped before any check: these assertions look for code by its text, and a call
    // that has been commented out still contains that text. Measured, not hypothetical -- with
    // `stopFiller()` commented out of playAnswer, the unstripped check stayed green (2026-10-14).
    private val manager = stripComments(File("src/main/java/com/benzn/grandtime/ask/AskManager.kt").readText())

    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")
    private val sounds = File("src/main/java/com/benzn/grandtime/ask/AskSounds.kt").readText()

    private fun cueBranch(): String {
        val start = manager.indexOf("AskCommand.PlayListeningCue ->")
        assertTrue("PlayListeningCue branch not found", start >= 0)
        return manager.substring(start, manager.indexOf("AskCommand.PlayThinkingCue", start))
    }

    @Test
    fun `the listening cue is awaited, not fired and forgotten`() {
        assertTrue(
            "a fire-and-forget cue plays over the open microphone and is sent as part of the question",
            cueBranch().contains("askStartAndAwait()"),
        )
    }

    @Test
    fun `after the wait the ask is re-checked before the microphone opens`() {
        val branch = cueBranch()
        assertTrue("must bail when the ask has moved on", branch.contains("core.state != AskState.Listening"))
        assertTrue("must bail when a newer cue has started", branch.contains("generation != listeningCueGeneration"))
        assertTrue("the bail must stop the remaining commands", branch.contains("return"))
    }

    @Test
    fun `the pause comes before the cue, and a release during it bails before the cue is spoken`() {
        val branch = cueBranch()
        val pause = branch.indexOf("delay(LISTENING_CUE_DELAY_MS)")
        val cue = branch.indexOf("askStartAndAwait()")
        assertTrue("the half-second pause is gone", pause >= 0)
        assertTrue("the pause must come BEFORE the cue, not after it", pause < cue)
        val between = branch.substring(pause, cue)
        assertTrue(
            "a press released during the pause must not then hear 'go ahead'",
            between.contains("core.state != AskState.Listening"),
        )
    }

    @Test
    fun `the filler is scheduled, never played inline or awaited`() {
        val start = manager.indexOf("AskCommand.PlayThinkingCue ->")
        val branch = manager.substring(start, manager.indexOf("\n", start))
        assertTrue("SendClip follows in the same list; waiting here delays the question", branch.contains("filler.schedule()"))
        assertTrue("an inline play would skip the pause", !branch.contains("sounds.thinking()"))
    }

    @Test
    fun `everything that sounds after the release silences a pending filler first`() {
        val error = manager.substring(manager.indexOf("AskCommand.PlayErrorCue ->"))
        assertTrue("error cue", error.substring(0, error.indexOf("\n")).contains("stopFiller()"))

        val answer = manager.substring(manager.indexOf("private suspend fun playAnswer"))
        assertTrue("answer", answer.substring(0, answer.indexOf("player.play(")).contains("stopFiller()"))

        // stopFiller must reach BOTH halves: the start that has not happened, and the player.
        val stop = manager.substring(manager.indexOf("private fun stopFiller()"))
        val body = stop.substring(0, stop.indexOf("}"))
        assertTrue("the pending start", body.contains("filler.cancel()"))
        assertTrue("the line already speaking", body.contains("sounds.stopThinking()"))
    }

    @Test
    fun `the wait can always give up`() {
        // A stuck player must not hold the ask in Listening with no microphone open.
        assertTrue(sounds.contains("withTimeoutOrNull"))
    }

    @Test
    fun `site voice keeps its short cue`() {
        // Talk starts the moment the key is down; a 1.3 s cue would eat the first words.
        val siteVoice = File("src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt").readText()
        assertTrue(siteVoice.contains("sounds.listening()"))
        assertTrue(!siteVoice.contains("askStartAndAwait"))
    }
}
