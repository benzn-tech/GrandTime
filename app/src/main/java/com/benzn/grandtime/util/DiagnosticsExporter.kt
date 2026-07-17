package com.benzn.grandtime.util

import android.content.Context
import android.os.Build
import com.benzn.grandtime.capture.MediaStorage
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Writes a diagnostics bundle (header + recent probe-log tail) to public storage for the user to share. */
object DiagnosticsExporter {

    private const val TAIL_LINES = 500

    /** Builds the diagnostics text (header + recent probe-log tail), writes it to the same
     *  user-visible <root>/FieldSight folder recordings live under —
     *  FieldSight/diagnostics/diag_<epochMillis>.txt — and returns that File (or null on failure). */
    fun export(context: Context, nowMs: Long = System.currentTimeMillis()): File? {
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val whenIso = OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault()).toString()
        val header = diagnosticsHeader(appVersion, Build.MODEL, Build.VERSION.SDK_INT, whenIso)
        val body = probeLogTail(context)
        val text = header + body

        return runCatching {
            val dir = File(MediaStorage.fieldSightRoot(context), "diagnostics").apply { mkdirs() }
            val file = File(dir, "diag_$nowMs.txt")
            file.writeText(text)
            file
        }.getOrNull()
    }

    /** Concatenates the probe-log dir's files and keeps only the last ~[TAIL_LINES] lines. */
    private fun probeLogTail(context: Context): String = runCatching {
        val probeDir = File(context.filesDir, "probe")
        val files = probeDir.listFiles()?.sortedBy { it.name } ?: return@runCatching ""
        val allLines = files.flatMap { it.readLines() }
        allLines.takeLast(TAIL_LINES).joinToString("\n")
    }.getOrDefault("")
}
