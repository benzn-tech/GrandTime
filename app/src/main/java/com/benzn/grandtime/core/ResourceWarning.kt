package com.benzn.grandtime.core

/** Severity of a low-resource condition; NONE means the resource is fine. */
enum class WarnLevel { NONE, WARNING, CRITICAL }

data class ResourceStatus(
    val storage: WarnLevel,
    val battery: WarnLevel,
    val freeBytes: Long,
    val batteryPct: Int,
) {
    val hasWarning get() = storage != WarnLevel.NONE || battery != WarnLevel.NONE
}

object ResourceThresholds {
    const val STORAGE_WARN_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    const val STORAGE_CRIT_BYTES = 512L * 1024 * 1024 // 512 MB
    const val BATTERY_WARN_PCT = 20
    const val BATTERY_CRIT_PCT = 10
}

/**
 * Assesses low-storage / low-battery risk of evidence loss mid-shift.
 * Charging suppresses the battery warning (device is recovering). Storage is always assessed.
 */
fun assessResources(freeBytes: Long, batteryPct: Int, charging: Boolean): ResourceStatus {
    val storage = when {
        freeBytes < ResourceThresholds.STORAGE_CRIT_BYTES -> WarnLevel.CRITICAL
        freeBytes < ResourceThresholds.STORAGE_WARN_BYTES -> WarnLevel.WARNING
        else -> WarnLevel.NONE
    }
    val battery = when {
        charging -> WarnLevel.NONE
        batteryPct <= ResourceThresholds.BATTERY_CRIT_PCT -> WarnLevel.CRITICAL
        batteryPct <= ResourceThresholds.BATTERY_WARN_PCT -> WarnLevel.WARNING
        else -> WarnLevel.NONE
    }
    return ResourceStatus(storage, battery, freeBytes, batteryPct)
}
