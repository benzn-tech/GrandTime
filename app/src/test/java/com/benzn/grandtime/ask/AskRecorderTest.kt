package com.benzn.grandtime.ask

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AskRecorderTest {
    @Test fun clip_file_is_under_cache_dir_and_m4a() {
        val cache = File("build/tmp/ask-test")
        val f = AskRecorder.clipFile(cache, 1_700_000_000_000L)
        assertTrue(f.path.replace('\\', '/').contains("ask-test"))
        assertTrue(f.name.endsWith(".m4a"))
        assertTrue(f.name.contains("1700000000000"))
    }

    @Test fun distinct_timestamps_give_distinct_files() {
        val cache = File("build/tmp/ask-test")
        val a = AskRecorder.clipFile(cache, 1L)
        val b = AskRecorder.clipFile(cache, 2L)
        assertTrue(a.name != b.name)
    }
}
