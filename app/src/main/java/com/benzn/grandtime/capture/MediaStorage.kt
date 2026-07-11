package com.benzn.grandtime.capture

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 卷选择(SD 优先)/目录/命名。spec §3。 */
class MediaStorage(
    private val volumesProvider: () -> List<File?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val dir: String, val prefix: String, val ext: String) {
        VIDEO("video", "VID", "mp4"),
        AUDIO("audio", "AUD", "m4a"),
        PHOTO("photo", "IMG", "jpg"),
    }

    fun root(): File {
        val volumes = volumesProvider().filterNotNull()
        require(volumes.isNotEmpty()) { "no storage volume available" }
        return if (volumes.size >= 2) volumes[1] else volumes[0]
    }

    fun mediaDir(kind: Kind): File = File(File(root(), "media"), kind.dir).apply { mkdirs() }

    fun newFile(kind: Kind, startMillis: Long = clock()): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMillis))
        val dir = mediaDir(kind)
        var candidate = File(dir, "${kind.prefix}_$stamp.${kind.ext}")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${kind.prefix}_${stamp}_$suffix.${kind.ext}")
            suffix++
        }
        return candidate
    }

    fun hasFreeSpace(minBytes: Long = 200L * 1024 * 1024): Boolean = root().usableSpace >= minBytes
}
