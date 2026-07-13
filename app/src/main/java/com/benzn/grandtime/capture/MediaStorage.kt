package com.benzn.grandtime.capture

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 公共存储:<root>/FieldSight/device/{video,audio,photo}。SD 优先(spec §3)。 */
class MediaStorage(
    private val rootProvider: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val dir: String, val prefix: String, val ext: String) {
        VIDEO("video", "VID", "mp4"),
        AUDIO("audio", "AUD", "m4a"),
        PHOTO("photo", "IMG", "jpg"),
    }

    fun mediaDir(kind: Kind): File =
        mediaSubdir(rootProvider(), kind.dir).apply { mkdirs() }

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

    fun hasFreeSpace(minBytes: Long = 200L * 1024 * 1024): Boolean = rootProvider().usableSpace >= minBytes

    companion object {
        /** SD 优先:枚举可移动卷取其目录;无则内置主共享存储。 */
        fun publicRoot(context: Context): File {
            val removable = runCatching {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                    ?.directory
            }.getOrNull()
            return removable ?: Environment.getExternalStorageDirectory()
        }

        /** 集中 <root>/FieldSight/device/<kindDir> 拼路径的唯一出处(供 SP2 future device→<user> 改名单点改)。 */
        fun mediaSubdir(root: File, kindDir: String): File =
            File(File(File(root, "FieldSight"), "device"), kindDir)
    }
}
