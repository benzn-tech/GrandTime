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
        val diskByPath = diskFiles.associateBy { it.path }
        // 孤儿行修复:曾开始但从未收尾(endedAt=null)的行,若磁盘文件确实存在
        // (如进程被杀导致 finalize 回调未跑),用磁盘元数据补齐收尾字段。
        records.filter { it.endedAt == null && it.filePath in diskByPath }.forEach { r ->
            val f = diskByPath.getValue(r.filePath)
            dao.finalize(r.id, f.modifiedMillis, durationReader(f.path) ?: 0L, f.sizeBytes)
        }
        val gone = records.filter { !it.missing && it.filePath !in diskByPath }.map { it.id }
        if (gone.isNotEmpty()) dao.markMissing(gone)
    }
}
