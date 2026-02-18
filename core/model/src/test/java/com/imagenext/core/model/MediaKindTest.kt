package com.imagenext.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {

    @Test
    fun `classifies image mime types as image`() {
        assertEquals(MediaKind.IMAGE, MediaKind.from("image/jpeg", "photo.jpg"))
    }

    @Test
    fun `classifies video mime types as video`() {
        assertEquals(MediaKind.VIDEO, MediaKind.from("video/mp4", "clip.mp4"))
    }

    @Test
    fun `falls back to extension when mime is generic`() {
        assertEquals(MediaKind.IMAGE, MediaKind.from("application/octet-stream", "upload.JPG"))
        assertEquals(MediaKind.VIDEO, MediaKind.from("application/octet-stream", "movie.MOV"))
    }

    @Test
    fun `returns unknown for unsupported mime and extension`() {
        assertEquals(MediaKind.UNKNOWN, MediaKind.from("application/pdf", "doc.pdf"))
    }
}
