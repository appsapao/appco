package com.yundac.apkcloner.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A clone that was built by this app. */
data class CloneRecord(
    val packageName: String,
    val label: String,
    val sourcePackage: String,
    val apkPath: String,
    val createdAt: Long,
    val sizeBytes: Long,
) {
    val apkFile: File get() = File(apkPath)
}

/**
 * Persists built clones as a small JSON index next to the APK files.
 *
 * Records whose APK has been deleted (or that were never built on this install)
 * are dropped on load, so the list never points at missing files.
 */
object CloneStore {

    private const val INDEX_FILE = "clones.json"

    fun directory(context: Context): File =
        File(context.filesDir, "clones").apply { mkdirs() }

    fun load(context: Context): List<CloneRecord> {
        val index = File(context.filesDir, INDEX_FILE)
        if (!index.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(index.readText())
            (0 until array.length()).mapNotNull { position ->
                val json = array.optJSONObject(position) ?: return@mapNotNull null
                val record = CloneRecord(
                    packageName = json.optString("package"),
                    label = json.optString("label"),
                    sourcePackage = json.optString("sourcePackage"),
                    apkPath = json.optString("apkPath"),
                    createdAt = json.optLong("createdAt"),
                    sizeBytes = json.optLong("sizeBytes"),
                )
                if (record.packageName.isEmpty() || !record.apkFile.isFile) null else record
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, record: CloneRecord) {
        val records = load(context).filterNot { it.packageName == record.packageName } + record
        write(context, records)
    }

    fun remove(context: Context, record: CloneRecord) {
        record.apkFile.delete()
        write(context, load(context).filterNot { it.packageName == record.packageName })
    }

    private fun write(context: Context, records: List<CloneRecord>) {
        val array = JSONArray()
        records.sortedByDescending { it.createdAt }.forEach { record ->
            array.put(
                JSONObject()
                    .put("package", record.packageName)
                    .put("label", record.label)
                    .put("sourcePackage", record.sourcePackage)
                    .put("apkPath", record.apkPath)
                    .put("createdAt", record.createdAt)
                    .put("sizeBytes", record.sizeBytes)
            )
        }
        File(context.filesDir, INDEX_FILE).writeText(array.toString())
    }
}
