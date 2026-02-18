package com.imagenext.core.model

import java.util.Locale

/**
 * Canonical media classification used across sync and UI layers.
 */
enum class MediaKind {
    IMAGE,
    VIDEO,
    UNKNOWN;

    companion object {
        private val IMAGE_EXTENSIONS = setOf(
            "jpg",
            "jpeg",
            "png",
            "gif",
            "bmp",
            "webp",
            "heic",
            "heif",
            "avif",
            "tif",
            "tiff",
            "dng",
            "raw",
            "arw",
            "cr2",
            "cr3",
            "nef",
            "orf",
            "rw2",
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4",
            "m4v",
            "mov",
            "webm",
        )

        private val GENERIC_MIME_TYPES = setOf(
            "application/octet-stream",
            "binary/octet-stream",
        )

        fun from(mimeType: String?, fileName: String?): MediaKind {
            val normalizedMime = mimeType?.trim()?.lowercase(Locale.US).orEmpty()
            if (normalizedMime.startsWith("image/")) return IMAGE
            if (normalizedMime.startsWith("video/")) return VIDEO

            val extension = fileName
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.US)
                .orEmpty()
            if (extension in IMAGE_EXTENSIONS) return IMAGE
            if (extension in VIDEO_EXTENSIONS) return VIDEO

            if (normalizedMime in GENERIC_MIME_TYPES || normalizedMime.isBlank()) {
                return UNKNOWN
            }

            return UNKNOWN
        }
    }
}
