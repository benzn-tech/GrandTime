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

    private val manager = File("src/main/java/com/benzn/grandtime/ask/AskManager.kt").readText()
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
