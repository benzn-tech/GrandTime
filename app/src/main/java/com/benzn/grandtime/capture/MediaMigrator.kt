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
            val destDir = MediaStorage.mediaSubdir(newRoot, kind).apply { mkdirs() }
            for (src in files) {
                val dest = File(destDir, src.name)
                val oldPath = src.absolutePath
                if (dest.exists()) {
                    // Only treat as already-migrated if content is identical (same length)
                    if (dest.length() == src.length()) {
                        src.delete()
                        continue
                    } else {
                        // Different content, same name: migrate source to non-colliding name
                        val newDest = findNonCollidingFile(destDir, src.name)
                        val ok = if (src.renameTo(newDest)) {
                            true
                        } else {
                            runCatching {
                                src.copyTo(newDest, overwrite = false)
                                if (newDest.length() == src.length()) { src.delete(); true } else { newDest.delete(); false }
                            }.getOrElse { newDest.delete(); false }
                        }
                        if (ok) { onMoved(oldPath, newDest); moved++ }
                        continue
                    }
                }
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

    private fun findNonCollidingFile(dir: File, name: String): File {
        val dotIndex = name.lastIndexOf('.')
        val stem = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = File(dir, "${stem}_$counter$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
    }
}
