package com.benzn.grandtime.util

/** Header for a diagnostics export. Pure/formatting only; probe-log body is appended by the caller. */
fun diagnosticsHeader(appVersion: String, deviceModel: String, androidSdk: Int, whenIso: String): String =
    buildString {
        appendLine("FieldSight diagnostics")
        appendLine("time: $whenIso")
        appendLine("app: $appVersion")
        appendLine("device: $deviceModel (SDK $androidSdk)")
        appendLine("----")
    }
