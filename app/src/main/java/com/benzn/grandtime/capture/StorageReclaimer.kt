package com.benzn.grandtime.capture

import com.benzn.grandtime.db.CaptureRecord
import java.io.File

/**
 * Deletes the app's own recordings to give space back, following [StoragePolicy].
 *
 * Two paths, because the thing that normally says whether a recording was uploaded -- the
 * database -- is exactly what stops working when the disk is full:
 *
 *  - [emergency] runs with no database, oldest file first, and only far enough to let the
 *    databases open. It is what lets a device at 0 bytes start at all.
 *  - [reclaim] runs once the database opens: uploaded recordings first, the live session never.
 *
 * NEVER touches anything outside a `video`, `audio` or `photo` directory under the FieldSight
 * root. On the device that prompted this, 4.7 GB of the 5.3 GB partition was someone's drawings,
 * and the app holds All-files access -- the only thing between it and those files is this rule.
 *
 * Everything that talks to the platform is a parameter, so both paths are tested on a real
 * temporary directory with the free-space numbers supplied by the test.
 */
class StorageReclaimer(
    /** `<storage root>/FieldSight`. */
    private val mediaRoot: () -> File,
    /** Usable space on the volume recordings are written to. */
    private val recordingFreeBytes: () -> Long,
    /** Usable space on the volume the databases live on. */
    private val databaseFreeBytes: () -> Long,
    /**
     * Whether recordings and databases share a volume. When they do not -- recordings on an SD
     * card -- deleting recordings frees nothing the databases can use, so the emergency path must
     * not delete anything at all.
     */
    private val sameVolume: () -> Boolean,
    private val log: (String) -> Unit = {},
) {

    /**
     * Frees space for the databases WITHOUT reading them. Returns the bytes deleted.
     *
     * Keeps deleting the oldest recordings, re-reading the database volume after each one, until
     * its free space ACTUALLY reaches [floorBytes] or there are no recordings left.
     *
     * NOT "delete an estimated number of bytes", and NOT "stop when a deletion gained nothing".
     * Both are wrong, measured on DQF2S: while that disk sat at 0 bytes, system processes kept
     * writing into the filesystem's reserved blocks, which apps cannot use. Deleting 369 MB of
     * recordings raised app-visible free space by only 255 MB -- the first ~114 MB only paid that
     * reserved band back. A guard that stopped when a deletion gained nothing (this function's
     * previous version) would have stopped after ONE file and left that device unable to start.
     *
     * Whether deleting recordings can help at all is a different question, and [sameVolume]
     * answers it: recordings on an SD card free nothing on internal storage, so nothing is deleted.
     */
    fun emergency(floorBytes: Long = StoragePolicy.EMERGENCY_FLOOR): Long {
        var free = databaseFreeBytes()
        if (free >= floorBytes) return 0
        if (!sameVolume()) {
            log("emergency reclaim skipped: recordings are on a different volume from the databases " +
                "(${free / StoragePolicy.MB} MB free there); deleting them would not help")
            return 0
        }
        val files = mediaFiles(mediaRoot()).filter { it.length() > 0 }.sortedBy { it.lastModified() }
        var freed = 0L
        var count = 0
        for (file in files) {
            if (free >= floorBytes) break
            val size = file.length()
            if (!file.delete()) continue
            freed += size
            count++
            free = databaseFreeBytes()
        }
        log("emergency reclaim: deleted $count file(s), ${freed / StoragePolicy.MB} MB; " +
            "database volume now ${free / StoragePolicy.MB} MB free" +
            (if (free < floorBytes) " -- STILL BELOW the ${floorBytes / StoragePolicy.MB} MB floor" else ""))
        return freed
    }

    /**
     * Deletes recordings, uploaded first, until [targetBytes] is free on the recording volume.
     * Returns the ids of the rows whose files were deleted, after passing them to [markMissing].
     *
     * A row whose file is outside the media tree is skipped rather than trusted: rows can still
     * point at pre-migration private paths, and the path in a row is not a licence to delete.
     */
    suspend fun reclaim(
        rows: List<CaptureRecord>,
        protectSessionId: String?,
        targetBytes: Long,
        markMissing: suspend (List<String>) -> Unit,
    ): List<String> {
        val free = recordingFreeBytes()
        if (free >= targetBytes) return emptyList()
        val root = mediaRoot()
        val candidates = rows.filter { !it.missing }.mapNotNull { r ->
            val file = File(r.filePath)
            if (!isMediaFile(root, file) || !file.exists()) return@mapNotNull null
            StoragePolicy.Candidate(
                id = r.id,
                path = r.filePath,
                sizeBytes = file.length(),
                startedAt = r.startedAt,
                uploaded = r.uploadStatus == UPLOADED,
                sessionId = r.sessionId,
            )
        }
        val plan = StoragePolicy.planEviction(candidates, free, targetBytes, protectSessionId)
        val deleted = plan.filter { File(it.path).delete() }
        if (deleted.isNotEmpty()) markMissing(deleted.map { it.id })
        val unsent = deleted.count { !it.uploaded }
        log(
            "rolling reclaim: deleted ${deleted.size} recording(s), " +
                "${deleted.sumOf { it.sizeBytes } / StoragePolicy.MB} MB" +
                (if (unsent > 0) ", $unsent of them NOT YET UPLOADED" else "") +
                "; free was ${free / StoragePolicy.MB} MB, target ${targetBytes / StoragePolicy.MB} MB",
        )
        return deleted.map { it.id }
    }

    private fun mediaFiles(root: File): List<File> =
        if (!root.isDirectory) emptyList()
        else root.walkTopDown().filter { it.isFile && isMediaFile(root, it) }.toList()

    companion object {
        /** The literal upload status the upload worker writes on success. */
        const val UPLOADED = "uploaded"

        private val KIND_DIRS = MediaStorage.Kind.values().map { it.dir }.toSet()

        /**
         * `<root>/<folder>/<video|audio|photo>/<file>`, and nothing else. Resolved canonically so
         * a `..` in a stored path cannot walk out of the tree.
         */
        fun isMediaFile(root: File, file: File): Boolean {
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
            val path = runCatching { file.canonicalFile }.getOrNull() ?: return false
            val kindDir = path.parentFile ?: return false
            val folder = kindDir.parentFile ?: return false
            return kindDir.name in KIND_DIRS && folder.parentFile?.path == rootPath
        }
    }
}
