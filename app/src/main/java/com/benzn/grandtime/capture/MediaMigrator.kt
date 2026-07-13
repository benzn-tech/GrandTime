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
            val destDir = MediaStorage.mediaSubdir(newRoot, "device", kind).apply { mkdirs() }
            for (src in files) moved += moveOne(src, destDir, onMoved)
        }
        return moved
    }

    companion object {
        /** FieldSight/<fromFolder>/{kind} → FieldSight/<toFolder>/{kind},复用实例逻辑的 crash-safe/同名保护。 */
        fun migrateFolder(root: File, fromFolder: String, toFolder: String, onMoved: (String, File) -> Unit): Int {
            if (fromFolder == toFolder) return 0
            var moved = 0
            for (kind in listOf("video", "audio", "photo")) {
                val srcDir = MediaStorage.mediaSubdir(root, fromFolder, kind)
                val files = srcDir.listFiles()?.filter { it.isFile } ?: continue
                val destDir = MediaStorage.mediaSubdir(root, toFolder, kind).apply { mkdirs() }
                for (src in files) moved += moveOne(src, destDir, onMoved)
            }
            return moved
        }

        /** 单文件搬迁:crash-safe rename-or-verified-copy;dest 同名同长视为已迁移(删 src 不计数);
         * 同名不同长则改用非冲突名。onMoved 只在真正搬移成功时回调,返回 1;否则返回 0。 */
        private fun moveOne(src: File, destDir: File, onMoved: (oldPath: String, newFile: File) -> Unit): Int {
            val dest = File(destDir, src.name)
            val oldPath = src.absolutePath
            if (dest.exists()) {
                // Only treat as already-migrated if content is identical (same length)
                if (dest.length() == src.length()) {
                    src.delete()
                    return 0
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
                    if (ok) { onMoved(oldPath, newDest); return 1 }
                    return 0
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
            if (ok) { onMoved(oldPath, dest); return 1 }
            return 0
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
}
