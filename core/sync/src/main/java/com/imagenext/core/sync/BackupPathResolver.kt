package com.imagenext.core.sync

import com.imagenext.core.model.BackupUploadStructure
import java.time.Instant
import java.time.ZoneId

internal fun resolveTargetRemoteFolder(
    backupRoot: String,
    uploadStructure: BackupUploadStructure,
    captureTimestampMs: Long,
): String {
    val normalizedRoot = normalizeRemotePath(backupRoot)
    if (uploadStructure == BackupUploadStructure.FLAT_FOLDER) {
        return normalizedRoot
    }
    val instant = if (captureTimestampMs > 0L) {
        Instant.ofEpochMilli(captureTimestampMs)
    } else {
        Instant.now()
    }
    val date = instant.atZone(ZoneId.systemDefault())
    val year = date.year
    val month = date.monthValue.toString().padStart(2, '0')
    return "$normalizedRoot/$year/$month"
}

internal fun buildRemoteFilePath(folder: String, fileName: String): String {
    val normalizedFolder = normalizeRemotePath(folder).trimEnd('/')
    val safeName = fileName.trim().ifBlank { "unnamed" }
    return "$normalizedFolder/$safeName"
}

internal fun normalizeRemotePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return "/"
    val withPrefix = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return withPrefix.replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
}

internal fun parentRemoteFolder(path: String): String {
    val normalized = normalizeRemotePath(path)
    val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "/")
    return if (parent.isBlank()) "/" else parent
}

internal fun remoteFileName(path: String): String =
    normalizeRemotePath(path).substringAfterLast('/', missingDelimiterValue = "")

internal fun computeRetryDelayMillis(attempt: Int): Long {
    val seconds = when (attempt.coerceAtLeast(1)) {
        1 -> 30L
        2 -> 120L
        else -> 300L
    }
    return seconds * 1000L
}
