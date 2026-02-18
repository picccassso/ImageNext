package com.imagenext.core.sync

import com.imagenext.core.model.MediaKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ThumbnailWorkerPolicyTest {

    @Test
    fun `video preview unsupported http codes are skipped`() {
        assertTrue(ThumbnailWorker.isUnsupportedVideoPreviewStatus(400))
        assertTrue(ThumbnailWorker.isUnsupportedVideoPreviewStatus(404))
        assertTrue(ThumbnailWorker.isUnsupportedVideoPreviewStatus(415))
        assertTrue(ThumbnailWorker.isUnsupportedVideoPreviewStatus(501))
        assertFalse(ThumbnailWorker.isUnsupportedVideoPreviewStatus(500))
    }

    @Test
    fun `only images attempt webdav-backed thumbnail fallback`() {
        assertTrue(ThumbnailWorker.shouldAttemptWebDavFallback(MediaKind.IMAGE))
        assertFalse(ThumbnailWorker.shouldAttemptWebDavFallback(MediaKind.VIDEO))
        assertFalse(ThumbnailWorker.shouldAttemptWebDavFallback(MediaKind.UNKNOWN))
    }

    @Test
    fun `only unreachable classification triggers worker retry`() {
        assertTrue(ThumbnailWorker.shouldRetryFromClassifiedError("unreachable"))
        assertFalse(ThumbnailWorker.shouldRetryFromClassifiedError("ssl"))
        assertFalse(ThumbnailWorker.shouldRetryFromClassifiedError("io"))
    }

    @Test
    fun `local frame fallback only applies for video with unsupported preview status`() {
        assertTrue(
            ThumbnailWorker.shouldAttemptLocalVideoFrameFallback(
                mediaKind = MediaKind.VIDEO,
                previewStatusCode = 415,
            )
        )
        assertFalse(
            ThumbnailWorker.shouldAttemptLocalVideoFrameFallback(
                mediaKind = MediaKind.IMAGE,
                previewStatusCode = 415,
            )
        )
        assertFalse(
            ThumbnailWorker.shouldAttemptLocalVideoFrameFallback(
                mediaKind = MediaKind.VIDEO,
                previewStatusCode = 500,
            )
        )
    }

    @Test
    fun `local frame failure classification retries connectivity errors`() {
        assertTrue(
            ThumbnailWorker.classifyLocalVideoFrameFailure(UnknownHostException("host")) ==
                ThumbnailWorker.LocalVideoFrameFailure.RETRY_UNREACHABLE
        )
        assertTrue(
            ThumbnailWorker.classifyLocalVideoFrameFailure(ConnectException("connect")) ==
                ThumbnailWorker.LocalVideoFrameFailure.RETRY_UNREACHABLE
        )
        assertTrue(
            ThumbnailWorker.classifyLocalVideoFrameFailure(SocketTimeoutException("timeout")) ==
                ThumbnailWorker.LocalVideoFrameFailure.RETRY_UNREACHABLE
        )
    }

    @Test
    fun `local frame failure classification maps io and unsupported separately`() {
        assertTrue(
            ThumbnailWorker.classifyLocalVideoFrameFailure(IOException("io")) ==
                ThumbnailWorker.LocalVideoFrameFailure.FAILED_IO
        )
        assertTrue(
            ThumbnailWorker.classifyLocalVideoFrameFailure(IllegalStateException("codec")) ==
                ThumbnailWorker.LocalVideoFrameFailure.SKIPPED_UNSUPPORTED
        )
    }
}
