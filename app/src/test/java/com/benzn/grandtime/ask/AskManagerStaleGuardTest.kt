package com.benzn.grandtime.ask

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AskManager cannot be built on the JVM (SoundPool, MediaPlayer, the recorder), so its stale-turn
 * guards are pinned at source level, like AskCueBeforeMicTest. Every continuation that can outlive
 * its ask must compare the generation it captured with core.generation before it touches the FSM.
 */
class AskManagerStaleGuardTest {
    private val manager = File("src/main/java/com/benzn/grandtime/ask/AskManager.kt").readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun body(marker: String): String {
        val start = manager.indexOf(marker)
        assertTrue("$marker not found", start >= 0)
        val next = manager.indexOf("\n    private ", start + marker.length)
        return manager.substring(start, if (next < 0) manager.length else next)
    }

    @Test fun the_player_callback_drops_a_stale_completion() {
        val b = body("private suspend fun playAnswer")
        val cb = b.substring(b.indexOf("player.play("))
        assertTrue(cb.indexOf("generation != core.generation") in 0 until cb.indexOf("core.onPlaybackDone()"))
    }

    @Test fun the_watchdog_drops_a_stale_timeout() {
        val b = body("private fun armPlayWatchdog")
        assertTrue(b.indexOf("generation != core.generation") in 0 until b.indexOf("core.onPlaybackTimeout()"))
    }

    @Test fun the_send_drops_a_stale_result_before_answering_or_failing() {
        val b = body("private suspend fun sendClip")
        assertTrue(b.contains("generation != core.generation"))
        assertTrue(b.indexOf("generation != core.generation") < b.indexOf("core.onAnswer("))
        assertTrue(b.contains("core.onAnswer(") && b.substringAfter("core.onAnswer(").substringBefore(")").contains("generation"))
    }

    @Test fun the_turn_result_is_dropped_when_stale_before_any_outcome_is_acted_on() {
        val b = body("private suspend fun sendClip")
        val run = b.indexOf("runner.run(")
        val guard = b.indexOf("generation != core.generation", run)
        assertTrue(run >= 0 && guard > run)
        assertTrue(guard < b.indexOf("when (outcome)"))
    }

    @Test fun an_old_turns_cleanup_only_silences_audio_it_still_owns() {
        val b = body("private fun turnAudio")
        val stop = b.substring(b.indexOf("override fun stopAll()"))
        assertTrue(stop.indexOf("generation != core.generation") in 0 until stop.indexOf("stopFiller()"))
    }
}
