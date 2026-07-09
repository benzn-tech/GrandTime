package com.benzn.grandtime.util

import java.io.File

/** 探针文本日志:单文件超过 maxBytes 时轮转为 probe.1.log,共保留 2 个文件。 */
class ProbeLog(private val dir: File, private val maxBytes: Long = 1_000_000L) {

    private val active: File get() = File(dir, "probe.log")

    @Synchronized
    fun append(line: String) {
        dir.mkdirs()
        if (active.exists() && active.length() > maxBytes) rotate()
        active.appendText(line + "\n")
    }

    private fun rotate() {
        val old = File(dir, "probe.1.log")
        old.delete()
        active.renameTo(old)
    }
}
