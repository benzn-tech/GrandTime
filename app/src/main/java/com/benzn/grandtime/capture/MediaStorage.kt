package com.benzn.grandtime.capture

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 公共存储:<root>/FieldSight/<folder>/{video,audio,photo}。Internal storage, not the SD card -- see [publicRoot]. */
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
        /**
         * Where new recordings go: internal shared storage, ALWAYS -- even with an SD card mounted.
         *
         * WHY. Recordings used to go to the SD card whenever one was mounted. On the F2SP the
         * card slot hangs off an SPI bus (mmc2 under spi0), and it cannot sustain what 1080p
         * video needs: 20 Mbps, ~2.4 MB/s, every second of the recording. Measured on a device
         * with a card (2026-10-12): the muxer's file writes blocked the encoder drain, the
         * encoder's pending outputs rose by 30 a second (all of them, 4 MB each), the device
         * ran out of memory within ~15 s of video alone, and the low-memory killer took the
         * app. A photo during video wrote three more large files to the same card and got
         * there sooner -- hence "photos are slow, then it crashes". The card then failed its
         * boot-time filesystem check by timing out. Same device, same build, recording to
         * internal storage: video + 8 photos, no stall, ~0.9 s per photo.
         *
         * Recordings already on a card stay where they are and are still scanned -- see
         * [scanRoots].
         */
        fun publicRoot(context: Context): File =
            pickRecordingRoot(Environment.getExternalStorageDirectory(), mountedRemovableRoots(context))

        /** The decision itself, apart from the platform lookups so it can be tested. The mounted
         *  cards are passed in to make the point explicit: they are not chosen. */
        @Suppress("UNUSED_PARAMETER")
        fun pickRecordingRoot(internal: File, mountedCards: List<File>): File = internal

        /** Every root that can hold recordings: the one new recordings go to, then any mounted
         *  card holding recordings from before [publicRoot] stopped choosing cards. The Files
         *  reconcile marks a row missing when its file is not under a scanned root, so scanning
         *  the recording root alone would quietly stop the upload of every unsent recording
         *  still on a card. */
        fun scanRoots(recordingRoot: File, mountedCards: List<File>): List<File> =
            (listOf(recordingRoot) + mountedCards).distinctBy { it.absolutePath }

        fun scanRoots(context: Context): List<File> = scanRoots(publicRoot(context), mountedRemovableRoots(context))

        private fun mountedRemovableRoots(context: Context): List<File> = runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { it.directory }
        }.getOrDefault(emptyList())

        /** 集中 <root>/FieldSight/<folder>/<kindDir> 拼路径的唯一出处(登出=device,登录=<user>_<sub>)。 */
        /**
         * Every storage volume the system reports, and which one recordings go to, as one log line.
         *
         * Exists because the SD-card fault cannot be diagnosed from a device without a card. This
         * ROM (MediaTek mt6768) declares two SD slots in its fstab and supports adoptable storage,
         * and a card set up as "phone storage" becomes a PRIVATE volume. Since 2026-10-12 no card
         * is chosen for recording (see [publicRoot]); the line still shows which volumes a device
         * has and in what state, e.g. a card the system could not mount.
         */
        fun describeVolumes(context: Context): String = runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val chosen = publicRoot(context).path
            val lines = sm.storageVolumes.map { v ->
                "${v.getDescription(context)} uuid=${v.uuid} primary=${v.isPrimary} " +
                    "removable=${v.isRemovable} emulated=${v.isEmulated} state=${v.state} " +
                    "dir=${v.directory?.path}"
            }
            "storage volumes (${lines.size}); recordings -> $chosen: " + lines.joinToString(" | ")
        }.getOrElse { "storage volumes: unavailable (${it.message})" }

        fun mediaSubdir(root: File, folder: String, kindDir: String): File =
            File(File(File(root, "FieldSight"), folder), kindDir)

        /** The user-visible <root>/FieldSight folder recordings live under (mediaSubdir's base,
         *  before the <folder> segment) — non-recording exports (e.g. diagnostics) belong here too. */
        fun fieldSightRoot(context: Context): File = File(publicRoot(context), "FieldSight")
    }
}
