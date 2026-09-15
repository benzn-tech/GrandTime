package com.benzn.grandtime.capture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every clip the app looks up by name is actually bundled.
 *
 * The lookup is `getIdentifier(name, "raw", ...)` at runtime, and a miss is designed to fall back to
 * a system tone. That fallback is what makes a typo invisible: the build is green, the device still
 * beeps, and the recorded prompt is simply never heard.
 */
class CaptureSoundsResourcesTest {

    private val raw = File("src/main/res/raw")

    private fun assertBundled(name: String) {
        val found = raw.listFiles().orEmpty().any { it.nameWithoutExtension == name }
        assertTrue("res/raw has no clip named '$name'", found)
    }

    @Test
    fun `every video and audio cue is bundled`() {
        for (media in CaptureSounds.Media.values()) {
            for (event in CaptureSounds.MEDIA_EVENTS) assertBundled("${media.prefix}_$event")
        }
    }

    @Test
    fun `the photo and low-battery cues are bundled`() {
        assertBundled(CaptureSounds.PHOTO)
        assertBundled(CaptureSounds.LOW_BATTERY)
    }

    @Test
    fun `the ask start cue is bundled`() {
        assertBundled("ask_agent")
    }
}
