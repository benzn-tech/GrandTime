package com.benzn.grandtime.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class AskPlayerTest {

    private fun freshCache(): File =
        File("build/tmp/askplayer-test-${UUID.randomUUID()}").apply { mkdirs() }

    private fun tempFile(dir: File): File = File(dir, AskPlayer.TEMP_NAME)

    /** Fake [AskPlayer.Player] recording lifecycle calls; optionally throws at a chosen stage. */
    private class FakePlayer(
        private val throwOnSetDataSource: Boolean = false,
        private val throwOnPrepare: Boolean = false,
    ) : AskPlayer.Player {
        var released = false
        var started = false
        var completeCallback: (() -> Unit)? = null
        var errorCallback: (() -> Unit)? = null

        override fun setDataSource(path: String) {
            if (throwOnSetDataSource) throw IOException("bad data source")
        }
        override fun setOnComplete(callback: () -> Unit) { completeCallback = callback }
        override fun setOnError(callback: () -> Unit) { errorCallback = callback }
        override fun prepare() {
            if (throwOnPrepare) throw IllegalStateException("prepare failed")
        }
        override fun start() { started = true }
        override fun release() { released = true }
    }

    private fun factoryFor(fake: FakePlayer) = object : AskPlayer.PlayerFactory {
        override fun create(): AskPlayer.Player = fake
    }

    @Test fun prepare_failure_does_not_throw_releases_player_and_deletes_temp() {
        val cache = freshCache()
        val fake = FakePlayer(throwOnPrepare = true)
        val player = AskPlayer(cache, factoryFor(fake))
        var doneCalls = 0

        // Must NOT throw even though prepare() throws.
        player.play(byteArrayOf(1, 2, 3, 4)) { doneCalls++ }

        assertEquals("onDone must fire exactly once on setup failure", 1, doneCalls)
        assertTrue("constructed player must be released (no native leak)", fake.released)
        assertFalse("player must not have been started after prepare failure", fake.started)
        assertFalse("temp file must be deleted on setup failure", tempFile(cache).exists())
    }

    @Test fun set_data_source_failure_does_not_throw_releases_player_and_deletes_temp() {
        val cache = freshCache()
        val fake = FakePlayer(throwOnSetDataSource = true)
        val player = AskPlayer(cache, factoryFor(fake))
        var doneCalls = 0

        player.play(byteArrayOf(9, 9, 9)) { doneCalls++ }

        assertEquals(1, doneCalls)
        assertTrue("player released on setDataSource failure", fake.released)
        assertFalse("temp file deleted on setDataSource failure", tempFile(cache).exists())
    }

    @Test fun successful_setup_starts_playback_and_writes_temp() {
        val cache = freshCache()
        val fake = FakePlayer()
        val player = AskPlayer(cache, factoryFor(fake))

        player.play(byteArrayOf(1, 2, 3))

        assertTrue("player must be started on successful setup", fake.started)
        assertFalse("player must not be released while playing", fake.released)
        assertTrue("temp file must exist while playing", tempFile(cache).exists())
    }

    @Test fun completion_callback_fires_onDone_releases_and_deletes_temp() {
        val cache = freshCache()
        val fake = FakePlayer()
        val player = AskPlayer(cache, factoryFor(fake))
        var doneCalls = 0

        player.play(byteArrayOf(1, 2, 3)) { doneCalls++ }
        assertTrue(tempFile(cache).exists())

        // Simulate MediaPlayer signalling normal completion.
        fake.completeCallback!!.invoke()

        assertEquals("onDone fires on completion", 1, doneCalls)
        assertTrue("player released after completion", fake.released)
        assertFalse("temp file deleted after completion", tempFile(cache).exists())
    }

    @Test fun async_error_callback_fires_onDone_releases_and_deletes_temp() {
        val cache = freshCache()
        val fake = FakePlayer()
        val player = AskPlayer(cache, factoryFor(fake))
        var doneCalls = 0

        player.play(byteArrayOf(1, 2, 3)) { doneCalls++ }

        // Simulate an async post-prepare playback error.
        fake.errorCallback!!.invoke()

        assertEquals("onDone fires on async error", 1, doneCalls)
        assertTrue("player released after async error", fake.released)
        assertFalse("temp file deleted after async error", tempFile(cache).exists())
    }

    @Test fun second_play_releases_prior_player_before_starting() {
        val cache = freshCache()
        val first = FakePlayer()
        val second = FakePlayer()
        val players = ArrayDeque(listOf(first, second))
        val factory = object : AskPlayer.PlayerFactory {
            override fun create(): AskPlayer.Player = players.removeFirst()
        }
        val player = AskPlayer(cache, factory)

        player.play(byteArrayOf(1))
        player.play(byteArrayOf(2))

        assertTrue("prior player must be released when a new play starts", first.released)
        assertTrue("new player must be started", second.started)
    }

    @Test fun explicit_release_releases_active_player_and_deletes_temp() {
        val cache = freshCache()
        val fake = FakePlayer()
        val player = AskPlayer(cache, factoryFor(fake))

        player.play(byteArrayOf(1, 2, 3))
        player.release()

        assertTrue("active player released on explicit release", fake.released)
        assertFalse("temp file deleted on explicit release", tempFile(cache).exists())
    }
}
