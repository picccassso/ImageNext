package com.imagenext.feature.viewer

import com.imagenext.core.data.ViewerRepository
import com.imagenext.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private fun mediaItem(remotePath: String, lastModified: Long = 1000L) = MediaItem(
        remotePath = remotePath,
        fileName = remotePath.substringAfterLast("/"),
        mimeType = "image/jpeg",
        size = 1024,
        lastModified = lastModified,
        etag = "etag",
        folderPath = "/photos",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() = runTest {
        val repo = FakeViewerRepository(emptyList())
        val vm = ViewerViewModel(repo, "/a.jpg")

        assertTrue(vm.uiState.value is ViewerUiState.Loading)
    }

    @Test
    fun `loads content and resolves initial index`() = runTest {
        val items = listOf(
            mediaItem("/a.jpg", 3000),
            mediaItem("/b.jpg", 2000),
            mediaItem("/c.jpg", 1000),
        )
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/b.jpg")

        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is ViewerUiState.Content)
        assertEquals(1, (state as ViewerUiState.Content).currentIndex)
        assertEquals("/b.jpg", state.currentItem.remotePath)
        assertEquals("/b.jpg", state.currentMediaSource?.remotePath)
    }

    @Test
    fun `error when empty list`() = runTest {
        val repo = FakeViewerRepository(emptyList())
        val vm = ViewerViewModel(repo, "/a.jpg")

        advanceUntilIdle()

        assertTrue(vm.uiState.value is ViewerUiState.Error)
    }

    @Test
    fun `error when initial path not found`() = runTest {
        val items = listOf(mediaItem("/a.jpg"))
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/nonexistent.jpg")

        advanceUntilIdle()

        assertTrue(vm.uiState.value is ViewerUiState.Error)
    }

    @Test
    fun `onPageChanged updates current index`() = runTest {
        val items = listOf(
            mediaItem("/a.jpg", 3000),
            mediaItem("/b.jpg", 2000),
            mediaItem("/c.jpg", 1000),
        )
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/a.jpg")

        advanceUntilIdle()

        vm.onPageChanged(2)
        val state = vm.uiState.value as ViewerUiState.Content
        assertEquals(2, state.currentIndex)
        assertEquals("/c.jpg", state.currentItem.remotePath)
    }

    @Test
    fun `onPageChanged ignores out-of-bounds index`() = runTest {
        val items = listOf(mediaItem("/a.jpg"), mediaItem("/b.jpg"))
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/a.jpg")

        advanceUntilIdle()

        vm.onPageChanged(5)
        val state = vm.uiState.value as ViewerUiState.Content
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `toggleMetadata flips showMetadata`() = runTest {
        val repo = FakeViewerRepository(listOf(mediaItem("/a.jpg")))
        val vm = ViewerViewModel(repo, "/a.jpg")

        advanceUntilIdle()

        assertFalse((vm.uiState.value as ViewerUiState.Content).showMetadata)

        vm.toggleMetadata()
        assertTrue((vm.uiState.value as ViewerUiState.Content).showMetadata)

        vm.toggleMetadata()
        assertFalse((vm.uiState.value as ViewerUiState.Content).showMetadata)
    }

    @Test
    fun `stress test - rapid page changes on large dataset`() = runTest {
        val items = (1..10_000).map { i ->
            mediaItem("/photo_$i.jpg", (10_000 - i).toLong() * 1000)
        }
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/photo_1.jpg")

        advanceUntilIdle()

        // Simulate rapid swiping through 100 pages
        for (i in 0 until 100) {
            vm.onPageChanged(i)
        }

        val state = vm.uiState.value as ViewerUiState.Content
        assertEquals(99, state.currentIndex)
        assertEquals(10_000, state.items.size)
    }

    @Test
    fun `stress test - rapid forward and backward swiping`() = runTest {
        val items = (1..1000).map { i ->
            mediaItem("/photo_$i.jpg", (1000 - i).toLong() * 1000)
        }
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/photo_500.jpg")

        advanceUntilIdle()

        // Simulate rapid forward/backward swiping
        var index = 499
        for (i in 0 until 200) {
            index = if (i % 2 == 0) (index + 1).coerceAtMost(999) else (index - 1).coerceAtLeast(0)
            vm.onPageChanged(index)
        }

        val state = vm.uiState.value as ViewerUiState.Content
        assertTrue(state.currentIndex in 0..999)
    }

    @Test
    fun `stress test - toggle metadata rapidly`() = runTest {
        val repo = FakeViewerRepository(listOf(mediaItem("/a.jpg")))
        val vm = ViewerViewModel(repo, "/a.jpg")

        advanceUntilIdle()

        // Toggle 100 times
        repeat(100) { vm.toggleMetadata() }

        // Even number of toggles should return to false
        assertFalse((vm.uiState.value as ViewerUiState.Content).showMetadata)
    }

    @Test
    fun `onPageChanged refreshes adjacent prefetch sources`() = runTest {
        val items = listOf(
            mediaItem("/a.jpg", 3000),
            mediaItem("/b.jpg", 2000),
            mediaItem("/c.jpg", 1000),
        )
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/b.jpg")

        advanceUntilIdle()
        val before = vm.uiState.value as ViewerUiState.Content
        assertEquals("/b.jpg", before.currentMediaSource?.remotePath)
        assertEquals(2, before.prefetchImageSources.size)

        vm.onPageChanged(0)
        val after = vm.uiState.value as ViewerUiState.Content
        assertEquals("/a.jpg", after.currentMediaSource?.remotePath)
        assertEquals(1, after.prefetchImageSources.size)
        assertEquals("/b.jpg", after.prefetchImageSources.first().remotePath)
    }

    @Test
    fun `prefetch list excludes adjacent videos`() = runTest {
        val items = listOf(
            mediaItem("/a.jpg", 3000),
            mediaItem("/b.mp4", 2000).copy(mimeType = "video/mp4"),
            mediaItem("/c.jpg", 1000),
        )
        val repo = FakeViewerRepository(items)
        val vm = ViewerViewModel(repo, "/b.mp4")

        advanceUntilIdle()

        val state = vm.uiState.value as ViewerUiState.Content
        assertEquals("/b.mp4", state.currentMediaSource?.remotePath)
        assertEquals(2, state.prefetchImageSources.size)
        assertTrue(state.prefetchImageSources.all { it.remotePath.endsWith(".jpg") })
    }

    @Test
    fun `uses album scoped query when album id is provided`() = runTest {
        val repo = FakeViewerRepository(listOf(mediaItem("/a.jpg")))
        ViewerViewModel(
            viewerRepository = repo,
            initialRemotePath = "/a.jpg",
            albumId = 42L,
        )

        advanceUntilIdle()

        assertEquals(42L, repo.lastRequestedAlbumId)
    }
}

/**
 * Fake [ViewerRepository] that returns in-memory data.
 * Uses a [StubMediaDao] that is never actually called since all methods are overridden.
 */
private class FakeViewerRepository(
    private val items: List<MediaItem>,
) : ViewerRepository(StubMediaDao()) {

    var lastRequestedAlbumId: Long? = null

    override suspend fun getMediaByPath(remotePath: String): MediaItem? {
        return items.find { it.remotePath == remotePath }
    }

    override suspend fun getAllMediaOrdered(): List<MediaItem> {
        return items
    }

    override suspend fun getMediaOrdered(albumId: Long?): List<MediaItem> {
        lastRequestedAlbumId = albumId
        return items
    }

    override fun getRemoteMediaSource(remotePath: String): RemoteMediaSource? {
        return RemoteMediaSource(
            remotePath = remotePath,
            fullResUrl = "https://example.com/full$remotePath",
            previewUrl = "https://example.com/preview$remotePath",
            authHeader = "Basic test",
        )
    }

    override fun getAdjacentRemoteMediaSources(
        items: List<MediaItem>,
        centerIndex: Int,
        window: Int,
    ): List<RemoteMediaSource> {
        if (items.isEmpty() || centerIndex !in items.indices || window <= 0) return emptyList()
        val start = (centerIndex - window).coerceAtLeast(0)
        val end = (centerIndex + window).coerceAtMost(items.lastIndex)
        return (start..end)
            .filter { it != centerIndex }
            .map { index -> getRemoteMediaSource(items[index].remotePath)!! }
    }
}
