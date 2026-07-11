package com.benzn.grandtime.db

import java.util.UUID

/** 文件↔DB 对账:磁盘有 DB 无 → 补插;DB 有磁盘无 → 标 missing。 */
class FilesReconciler(
    private val dao: CaptureRecordDao,
    private val durationReader: (path: String) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class DiskFile(
        val path: String,
        val name: String,
        val kind: String,
        val sizeBytes: Long,
        val modifiedMillis: Long,
    )

    suspend fun reconcile(diskFiles: List<DiskFile>) {
        val records = dao.listAll()
        val knownPaths = records.map { it.filePath }.toSet()
        diskFiles.filter { it.path !in knownPaths }.forEach { f ->
            dao.insert(
                CaptureRecord(
                    id = UUID.randomUUID().toString(),
                    kind = f.kind,
                    filePath = f.path,
                    fileName = f.name,
                    startedAt = f.modifiedMillis,
                    endedAt = f.modifiedMillis,
                    durationMs = durationReader(f.path),
                    sizeBytes = f.sizeBytes,
                    codec = "unknown",
                    sessionId = "reconciled",
                    createdAt = clock(),
                )
            )
        }
        val diskPaths = diskFiles.map { it.path }.toSet()
        val gone = records.filter { !it.missing && it.filePath !in diskPaths }.map { it.id }
        if (gone.isNotEmpty()) dao.markMissing(gone)
    }
}
