package com.imagenext.core.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.imagenext.core.model.BackupMediaTypes
import java.security.MessageDigest

/** Local MediaStore bucket projection used by backup setup UI. */
data class LocalMediaAlbum(
    val bucketId: String,
    val displayName: String,
    val itemCount: Int,
)

/** Media row projected for backup upload queueing. */
data class DetectedLocalMedia(
    val stableKey: String,
    val contentUri: String,
    val bucketId: String,
    val displayName: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val dateTaken: Long,
    val dateModified: Long,
)

/**
 * Local media detector for backup queue population.
 *
 * Supports MediaStore and SAF tree scans depending on user policy.
 */
class LocalMediaDetector(
    private val context: Context,
) {

    fun hasReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val imagePerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES,
            ) == PackageManager.PERMISSION_GRANTED
            val videoPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) == PackageManager.PERMISSION_GRANTED
            imagePerm || videoPerm
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun queryAlbums(mediaTypes: BackupMediaTypes): List<LocalMediaAlbum> {
        if (!hasReadPermission()) return emptyList()

        val projection = arrayOf(
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns._ID,
        )
        val where = buildTypeSelection(mediaTypes)
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val uri = MediaStore.Files.getContentUri("external")

        val counts = linkedMapOf<String, Pair<String, Int>>()
        context.contentResolver.query(uri, projection, where, null, sortOrder)?.use { cursor ->
            val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdIdx) ?: continue
                val displayName = cursor.getString(bucketNameIdx)?.takeIf { it.isNotBlank() } ?: "Unknown"
                val existing = counts[bucketId]
                if (existing == null) {
                    counts[bucketId] = displayName to 1
                } else {
                    counts[bucketId] = existing.first to (existing.second + 1)
                }
            }
        }

        return counts.entries
            .map { (bucketId, pair) ->
                LocalMediaAlbum(
                    bucketId = bucketId,
                    displayName = pair.first,
                    itemCount = pair.second,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    fun scanAllMedia(mediaTypes: BackupMediaTypes): List<DetectedLocalMedia> {
        if (!hasReadPermission()) return emptyList()
        return scanMediaStoreRows(
            where = buildTypeSelection(mediaTypes),
            mediaTypes = mediaTypes,
        )
    }

    fun scanSelectedMedia(
        selectedBucketIds: Set<String>,
        mediaTypes: BackupMediaTypes,
    ): List<DetectedLocalMedia> {
        if (!hasReadPermission()) return emptyList()
        if (selectedBucketIds.isEmpty()) return emptyList()

        val where = buildTypeAndBucketSelection(selectedBucketIds, mediaTypes)
        return scanMediaStoreRows(where = where, mediaTypes = mediaTypes)
    }

    fun scanTreeUris(
        treeUris: List<String>,
        mediaTypes: BackupMediaTypes,
    ): List<DetectedLocalMedia> {
        if (treeUris.isEmpty()) return emptyList()

        val results = mutableListOf<DetectedLocalMedia>()
        for (treeUri in treeUris.distinct()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: continue
            val rootName = tree.name?.takeIf { it.isNotBlank() } ?: "Folder"

            val stack = ArrayDeque<Pair<DocumentFile, String>>()
            stack.add(tree to "")

            while (stack.isNotEmpty() && results.size < MAX_TREE_SCAN_ITEMS) {
                val (node, relativeDir) = stack.removeLast()
                if (!node.canRead()) continue

                if (node.isDirectory) {
                    node.listFiles().forEach { child ->
                        val childName = child.name.orEmpty()
                        val childRelative = if (relativeDir.isBlank()) childName else "$relativeDir/$childName"
                        stack.add(child to childRelative)
                    }
                    continue
                }

                val fileName = node.name?.takeIf { it.isNotBlank() } ?: continue
                val mimeType = node.type.orEmpty()
                if (!isAllowedMediaType(mimeType = mimeType, fileName = fileName, mediaTypes = mediaTypes)) {
                    continue
                }

                val modified = node.lastModified().takeIf { it > 0L } ?: 0L
                val dateTaken = modified
                val size = node.length().coerceAtLeast(0L)
                val stableKey = computeStableKey(
                    bucketId = treeUri,
                    fileName = relativeDir,
                    size = size,
                    dateTaken = dateTaken,
                    dateModified = modified,
                )

                results += DetectedLocalMedia(
                    stableKey = stableKey,
                    contentUri = node.uri.toString(),
                    bucketId = treeUri,
                    displayName = rootName,
                    fileName = fileName,
                    mimeType = mimeType.ifBlank { guessMimeTypeFromName(fileName) },
                    size = size,
                    dateTaken = dateTaken,
                    dateModified = modified,
                )
            }
        }

        return results
    }

    fun computeStableKey(
        bucketId: String,
        fileName: String,
        size: Long,
        dateTaken: Long,
        dateModified: Long,
    ): String {
        val raw = "$bucketId|$fileName|$size|$dateTaken|$dateModified"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(byte)) }
        }
    }

    private fun scanMediaStoreRows(
        where: String,
        mediaTypes: BackupMediaTypes,
    ): List<DetectedLocalMedia> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val uri = MediaStore.Files.getContentUri("external")

        val rows = ArrayList<DetectedLocalMedia>(1024)
        context.contentResolver.query(uri, projection, where, null, sortOrder)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val displayNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeTypeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateTakenIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val dateModifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val bucketId = cursor.getString(bucketIdIdx) ?: continue
                val bucketName = cursor.getString(bucketNameIdx)?.takeIf { it.isNotBlank() } ?: "Unknown"
                val fileName = cursor.getString(displayNameIdx)?.takeIf { it.isNotBlank() } ?: continue
                val mimeType = cursor.getString(mimeTypeIdx)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val size = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                val dateTaken = cursor.getLong(dateTakenIdx)
                val dateModified = cursor.getLong(dateModifiedIdx) * 1000L
                val normalizedDateTaken = if (dateTaken > 0L) dateTaken else dateModified

                if (!isAllowedMediaType(mimeType, fileName, mediaTypes)) continue

                val stableKey = computeStableKey(
                    bucketId = bucketId,
                    fileName = fileName,
                    size = size,
                    dateTaken = normalizedDateTaken,
                    dateModified = dateModified,
                )
                val contentUri = ContentUris.withAppendedId(uri, id).toString()

                rows += DetectedLocalMedia(
                    stableKey = stableKey,
                    contentUri = contentUri,
                    bucketId = bucketId,
                    displayName = bucketName,
                    fileName = fileName,
                    mimeType = mimeType,
                    size = size,
                    dateTaken = normalizedDateTaken,
                    dateModified = dateModified,
                )
            }
        }

        return rows
    }

    private fun isAllowedMediaType(
        mimeType: String,
        fileName: String,
        mediaTypes: BackupMediaTypes,
    ): Boolean {
        val lowerMime = mimeType.lowercase()
        return when {
            lowerMime.startsWith("image/") -> mediaTypes.uploadPhotos
            lowerMime.startsWith("video/") -> mediaTypes.uploadVideos
            else -> {
                val extension = fileName.substringAfterLast('.', "").lowercase()
                if (extension in IMAGE_EXTENSIONS) mediaTypes.uploadPhotos
                else if (extension in VIDEO_EXTENSIONS) mediaTypes.uploadVideos
                else false
            }
        }
    }

    private fun guessMimeTypeFromName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            extension in IMAGE_EXTENSIONS -> "image/*"
            extension in VIDEO_EXTENSIONS -> "video/*"
            else -> "application/octet-stream"
        }
    }

    private fun buildTypeSelection(mediaTypes: BackupMediaTypes): String {
        return when {
            mediaTypes.uploadPhotos && mediaTypes.uploadVideos ->
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                    "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            mediaTypes.uploadPhotos ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
            mediaTypes.uploadVideos ->
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            else ->
                "1=0"
        }
    }

    private fun buildTypeAndBucketSelection(
        selectedBucketIds: Set<String>,
        mediaTypes: BackupMediaTypes,
    ): String {
        val bucketList = selectedBucketIds.joinToString(separator = ",") { "'${it.replace("'", "''")}'" }
        return "(${buildTypeSelection(mediaTypes)}) AND ${MediaStore.Files.FileColumns.BUCKET_ID} IN ($bucketList)"
    }

    private companion object {
        const val MAX_TREE_SCAN_ITEMS = 75_000
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "tif", "tiff", "dng")
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "avi", "3gp")
    }
}
