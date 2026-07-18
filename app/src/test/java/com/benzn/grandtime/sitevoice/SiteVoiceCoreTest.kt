package com.benzn.grandtime.sitevoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SiteVoiceCoreTest {
    private fun core() = SiteVoiceCore()
    private fun clip(k: String) = VoiceClip(File(k), k, "u9", "2026-07-18T00:00:00Z", 3)

    @Test fun down_when_idle_and_free_starts_recording() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = false, askActive = false)
        assertEquals(SiteVoiceState.Recording, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.PlayTalkStartCue))
        assertTrue(cmds.contains(SiteVoiceCommand.StartRecording))
        assertTrue(cmds.contains(SiteVoiceCommand.ArmCapTimer))
    }

    @Test fun down_while_video_recording_is_busy_and_stays_idle() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = true, askActive = false)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayBusyCue), cmds)
    }

    @Test fun down_while_ask_active_is_busy_and_stays_idle() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = false, askActive = true)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayBusyCue), cmds)
    }

    @Test fun up_while_recording_uploads_and_sends() {
        val c = core().apply { onSosDown(false, false) }
        val cmds = c.onSosUp()
        assertEquals(SiteVoiceState.Sending, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.CancelCapTimer))
        assertTrue(cmds.contains(SiteVoiceCommand.StopRecording))
        assertTrue(cmds.contains(SiteVoiceCommand.UploadAndSend))
    }

    @Test fun cap_reached_while_recording_auto_sends() {
        val c = core().apply { onSosDown(false, false) }
        val cmds = c.onCapReached()
        assertEquals(SiteVoiceState.Sending, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.StopRecording))
        assertTrue(cmds.contains(SiteVoiceCommand.UploadAndSend))
    }

    @Test fun send_ok_with_empty_queue_returns_to_idle() {
        val c = core().apply { onSosDown(false, false); onSosUp() }
        val cmds = c.onSendResult(ok = true)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertTrue(cmds.isEmpty())
    }

    @Test fun send_failure_plays_error_and_returns_to_idle() {
        val c = core().apply { onSosDown(false, false); onSosUp() }
        val cmds = c.onSendResult(ok = false)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.PlayErrorCue))
    }

    @Test fun inbound_when_idle_plays_immediately() {
        val c = core()
        val cmds = c.onClipReady(clip("a"))
        assertEquals(SiteVoiceState.Playing, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayClip(clip("a"))), cmds)
    }

    @Test fun inbound_while_recording_is_queued() {
        val c = core().apply { onSosDown(false, false) }
        val cmds = c.onClipReady(clip("a"))
        assertTrue(cmds.isEmpty())
        assertEquals(1, c.queueSize)
        assertEquals(SiteVoiceState.Recording, c.state)
    }

    @Test fun queued_clip_plays_after_send_completes() {
        val c = core().apply { onSosDown(false, false); onClipReady(clip("a")); onSosUp() }
        val cmds = c.onSendResult(ok = true)
        assertEquals(SiteVoiceState.Playing, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayClip(clip("a"))), cmds)
        assertEquals(0, c.queueSize)
    }

    @Test fun playback_done_plays_next_queued_then_idles() {
        val c = core().apply { onClipReady(clip("a")); onClipReady(clip("b")) } // a playing, b queued
        val next = c.onPlaybackDone()
        assertEquals(listOf(SiteVoiceCommand.PlayClip(clip("b"))), next)
        assertEquals(SiteVoiceState.Playing, c.state)
        val done = c.onPlaybackDone()
        assertTrue(done.isEmpty())
        assertEquals(SiteVoiceState.Idle, c.state)
    }

    @Test fun down_while_playing_is_ignored() {
        val c = core().apply { onClipReady(clip("a")) } // Playing
        assertTrue(c.onSosDown(false, false).isEmpty())
        assertEquals(SiteVoiceState.Playing, c.state)
    }

    @Test fun cap_after_up_does_not_second_send() {
        val c = core().apply { onSosDown(false, false); onSosUp() } // Sending
        assertTrue(c.onCapReached().isEmpty())
        assertEquals(SiteVoiceState.Sending, c.state)
    }
}
