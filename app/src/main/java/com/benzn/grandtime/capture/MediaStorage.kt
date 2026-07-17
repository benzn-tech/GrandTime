package com.benzn.grandtime.capture

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 公共存储:<root>/FieldSight/<folder>/{video,audio,photo}。SD 优先(spec §3)。 */
class MediaStorage(
    private val rootProvider: () -> File,
    private val scopeProvider: () -> com.benzn.grandtime.auth.MediaScope =
        { com.benzn.grandtime.auth.MediaScope("device", null) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val dir: String, val prefix: String, val ext: String) {
        VIDEO("video", "VID", "mp4"),
        AUDIO("audio", "AUD", "wav"),
        PHOTO("photo", "IMG", "jpg"),
    }

    fun mediaDir(kind: Kind): File =
        mediaSubdir(rootProvider(), scopeProvider().folder, kind.dir).apply { mkdirs() }

    fun newFile(kind: Kind, startMillis: Long = clock()): File {
        // Dashed so the pipeline BUG-01 regex \d{4}-\d{2}-\d{2}_(\d{2})-(\d{2})-(\d{2}) parses the
        // time out of the filename (G4); device-local timezone stays — already NZ.
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startMillis))
        val dir = mediaDir(kind)
        val prefix = scopeProvider().namePrefix ?: kind.prefix
        var candidate = File(dir, "${prefix}_$stamp.${kind.ext}")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${prefix}_${stamp}_$suffix.${kind.ext}")
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

        /** 集中 <root>/FieldSight/<folder>/<kindDir> 拼路径的唯一出处(登出=device,登录=<user>_<sub>)。 */
        fun mediaSubdir(root: File, folder: String, kindDir: String): File =
            File(File(File(root, "FieldSight"), folder), kindDir)

        /** The user-visible <root>/FieldSight folder recordings live under (mediaSubdir's base,
         *  before the <folder> segment) — non-recording exports (e.g. diagnostics) belong here too. */
        fun fieldSightRoot(context: Context): File = File(publicRoot(context), "FieldSight")
    }
}
