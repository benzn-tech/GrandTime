package com.benzn.grandtime.capture

import java.io.File

// Migrate old app private media files to public directory. Idempotent.
class MediaMigrator(
    private val oldRoot: File,
    private val newRoot: File,
    private val onMoved: (oldPath: String, newFile: File) -> Unit,
) {
    fun migrate(): Int {
        var moved = 0
        for (kind in listOf("video", "audio", "photo")) {
            val srcDir = File(File(oldRoot, "media"), kind)
            val files = srcDir.listFiles()?.filter { it.isFile } ?: continue
            val destDir = File(File(File(newRoot, "FieldSight"), "device"), kind).apply { mkdirs() }
            for (src in files) {
                val dest = File(destDir, src.name)
                val oldPath = src.absolutePath
                if (dest.exists()) { src.delete(); continue }
                val ok = if (src.renameTo(dest)) {
                    true
                } else {
                    runCatching {
                        src.copyTo(dest, overwrite = false)
                        if (dest.length() == src.length()) { src.delete(); true } else { dest.delete(); false }
                    }.getOrElse { dest.delete(); false }
                }
                if (ok) { onMoved(oldPath, dest); moved++ }
            }
        }
        return moved
    }
}
