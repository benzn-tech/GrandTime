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
                if (dest.exists()) { src.delete(); continue }
                val oldPath = src.absolutePath
                if (src.renameTo(dest) || (src.copyTo(dest, overwrite = false).exists().also { src.delete() })) {
                    onMoved(oldPath, dest)
                    moved++
                }
            }
        }
        return moved
    }
}
