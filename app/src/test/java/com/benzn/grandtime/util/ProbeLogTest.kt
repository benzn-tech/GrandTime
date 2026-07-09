package com.benzn.grandtime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProbeLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `append writes lines to active file`() {
        val log = ProbeLog(tmp.root)
        log.append("hello")
        log.append("world")
        assertEquals("hello\nworld\n", File(tmp.root, "probe.log").readText())
    }

    @Test
    fun `rotates when active file exceeds max`() {
        val log = ProbeLog(tmp.root, maxBytes = 10)
        log.append("0123456789ABC") // 超限
        log.append("next")           // 触发轮转后写入新文件
        assertTrue(File(tmp.root, "probe.1.log").exists())
        assertEquals("next\n", File(tmp.root, "probe.log").readText())
    }

    @Test
    fun `old rotated file is replaced not accumulated`() {
        val log = ProbeLog(tmp.root, maxBytes = 5)
        log.append("aaaaaaaa")
        log.append("bbbbbbbb")
        log.append("c")
        assertFalse(File(tmp.root, "probe.2.log").exists())
    }
}
