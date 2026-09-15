package com.benzn.grandtime.update

import android.content.Context
import org.json.JSONObject
import java.io.File

/** A downloaded, verified build waiting to be installed. */
data class ReadyUpdate(
    val versionCode: Long,
    val versionName: String,
    val minVersionCode: Long,
    val sha256: String,
    val fileName: String,
    val notes: String,
)

/**
 * The update directory in the app's private files: at most one verified APK and the record that
 * says it is verified. The record is written only after verification and only then does the APK get
 * its final name, so a download interrupted part-way never looks ready.
 */
class AppUpdateStore(val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR))

    fun read(): ReadyUpdate? = runCatching {
        val j = JSONObject(File(dir, RECORD).readText())
        ReadyUpdate(
            versionCode = j.getLong("versionCode"),
            versionName = j.getString("versionName"),
            minVersionCode = j.optLong("minVersionCode", 0),
            sha256 = j.getString("sha256"),
            fileName = j.getString("fileName"),
            notes = j.optString("notes", ""),
        )
    }.getOrNull()

    fun write(u: ReadyUpdate) {
        dir.mkdirs()
        val tmp = File(dir, "$RECORD.tmp")
        tmp.writeText(
            JSONObject()
                .put("versionCode", u.versionCode)
                .put("versionName", u.versionName)
                .put("minVersionCode", u.minVersionCode)
                .put("sha256", u.sha256)
                .put("fileName", u.fileName)
                .put("notes", u.notes)
                .toString(),
        )
        check(tmp.renameTo(File(dir, RECORD)) || File(dir, RECORD).let { it.delete() && tmp.renameTo(it) }) {
            "could not store the update record"
        }
    }

    fun apkFile(u: ReadyUpdate): File = File(dir, u.fileName)

    fun partFile(versionCode: Long): File = File(dir, "$versionCode.apk.part")

    fun finalFile(versionCode: Long): File = File(dir, "$versionCode.apk")

    /** Deletes every download and the record: a newer release supersedes, an installed one is done. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val DIR = "updates"
        private const val RECORD = "ready.json"
    }
}
