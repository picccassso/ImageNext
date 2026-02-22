package com.imagenext.core.data

import com.imagenext.core.database.dao.AlbumDao
import com.imagenext.core.database.dao.MediaDao
import com.imagenext.core.database.entity.AlbumEntity
import com.imagenext.core.database.entity.AlbumMediaCrossRefEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class AlbumRepositoryBulkAddTest {

    @Test
    fun `bulk add inserts new refs and touches album once`() {
        val albumDaoHarness = AlbumDaoHarness(albumExists = true)
        val mediaDao = mediaDao(existingPaths = setOf("/a.jpg", "/b.jpg", "/c.jpg"))
        val repository = AlbumRepository(albumDao = albumDaoHarness.dao, mediaDao = mediaDao)

        val result = runBlocking {
            repository.addMediaToAlbumBulk(
                albumId = TEST_ALBUM_ID,
                mediaRemotePaths = listOf("/a.jpg", "/b.jpg", "/c.jpg"),
            )
        }

        assertEquals(3, result.addedCount)
        assertEquals(0, result.alreadyInAlbumCount)
        assertEquals(0, result.mediaNotFoundCount)
        assertEquals(false, result.albumNotFound)
        assertEquals(1, albumDaoHarness.touchCalls)
        assertEquals(setOf("/a.jpg", "/b.jpg", "/c.jpg"), albumDaoHarness.insertedPaths)
    }

    @Test
    fun `bulk add reports already-in-album rows without touch`() {
        val albumDaoHarness = AlbumDaoHarness(
            albumExists = true,
            initialPaths = mutableSetOf("/a.jpg", "/b.jpg", "/c.jpg"),
        )
        val mediaDao = mediaDao(existingPaths = setOf("/a.jpg", "/b.jpg", "/c.jpg"))
        val repository = AlbumRepository(albumDao = albumDaoHarness.dao, mediaDao = mediaDao)

        val result = runBlocking {
            repository.addMediaToAlbumBulk(
                albumId = TEST_ALBUM_ID,
                mediaRemotePaths = listOf("/a.jpg", "/b.jpg", "/c.jpg"),
            )
        }

        assertEquals(0, result.addedCount)
        assertEquals(3, result.alreadyInAlbumCount)
        assertEquals(0, result.mediaNotFoundCount)
        assertEquals(false, result.albumNotFound)
        assertEquals(0, albumDaoHarness.touchCalls)
    }

    @Test
    fun `bulk add handles mixed added already and missing`() {
        val albumDaoHarness = AlbumDaoHarness(
            albumExists = true,
            initialPaths = mutableSetOf("/a.jpg"),
        )
        val mediaDao = mediaDao(existingPaths = setOf("/a.jpg", "/b.jpg"))
        val repository = AlbumRepository(albumDao = albumDaoHarness.dao, mediaDao = mediaDao)

        val result = runBlocking {
            repository.addMediaToAlbumBulk(
                albumId = TEST_ALBUM_ID,
                mediaRemotePaths = listOf("/a.jpg", "/b.jpg", "/c.jpg"),
            )
        }

        assertEquals(1, result.addedCount)
        assertEquals(1, result.alreadyInAlbumCount)
        assertEquals(1, result.mediaNotFoundCount)
        assertEquals(false, result.albumNotFound)
        assertEquals(1, albumDaoHarness.touchCalls)
        assertTrue(albumDaoHarness.insertedPaths.contains("/b.jpg"))
    }

    @Test
    fun `bulk add returns album-not-found when album does not exist`() {
        val albumDaoHarness = AlbumDaoHarness(albumExists = false)
        val mediaDao = mediaDao(existingPaths = setOf("/a.jpg", "/b.jpg"))
        val repository = AlbumRepository(albumDao = albumDaoHarness.dao, mediaDao = mediaDao)

        val result = runBlocking {
            repository.addMediaToAlbumBulk(
                albumId = TEST_ALBUM_ID,
                mediaRemotePaths = listOf("/a.jpg", "/b.jpg"),
            )
        }

        assertEquals(0, result.addedCount)
        assertEquals(0, result.alreadyInAlbumCount)
        assertEquals(2, result.mediaNotFoundCount)
        assertEquals(true, result.albumNotFound)
        assertEquals(0, albumDaoHarness.touchCalls)
    }

    @Test
    fun `bulk add rejects system albums`() {
        val albumDaoHarness = AlbumDaoHarness(albumExists = true)
        val mediaDao = mediaDao(existingPaths = setOf("/a.jpg"))
        val repository = AlbumRepository(albumDao = albumDaoHarness.dao, mediaDao = mediaDao)

        val result = runBlocking {
            repository.addMediaToAlbumBulk(
                albumId = AlbumRepository.SYSTEM_ALBUM_RECENTS_ID,
                mediaRemotePaths = listOf("/a.jpg"),
            )
        }

        assertEquals(0, result.addedCount)
        assertEquals(0, result.alreadyInAlbumCount)
        assertEquals(1, result.mediaNotFoundCount)
        assertEquals(true, result.albumNotFound)
        assertEquals(0, albumDaoHarness.touchCalls)
    }

    private class AlbumDaoHarness(
        private val albumExists: Boolean,
        initialPaths: MutableSet<String> = mutableSetOf(),
    ) {
        val insertedPaths: MutableSet<String> = initialPaths
        var touchCalls: Int = 0

        val dao: AlbumDao = proxy(AlbumDao::class.java, AlbumDaoHandler())

        private inner class AlbumDaoHandler : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                return when (method.name) {
                    "getAlbum" -> {
                        if (albumExists) {
                            AlbumEntity(
                                albumId = TEST_ALBUM_ID,
                                name = "Trips",
                                normalizedName = "trips",
                                createdAt = 1L,
                                updatedAt = 1L,
                            )
                        } else {
                            null
                        }
                    }

                    "insertAlbumMedia" -> {
                        val payload = args?.firstOrNull()
                        when (payload) {
                            is AlbumMediaCrossRefEntity -> {
                                if (insertedPaths.add(payload.mediaRemotePath)) 1L else -1L
                            }

                            is List<*> -> {
                                payload.map { raw ->
                                    val ref = raw as AlbumMediaCrossRefEntity
                                    if (insertedPaths.add(ref.mediaRemotePath)) 1L else -1L
                                }
                            }

                            else -> error("Unexpected insert payload: $payload")
                        }
                    }

                    "touchAlbum" -> {
                        touchCalls += 1
                        1
                    }

                    "toString" -> "AlbumDaoHarness"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> proxy === args?.get(0)
                    else -> throw UnsupportedOperationException("AlbumDao.${method.name} not implemented in test harness")
                }
            }
        }
    }

    private fun mediaDao(existingPaths: Set<String>): MediaDao {
        return proxy(MediaDao::class.java) { proxy, method, args ->
            when (method.name) {
                "getExistingRemotePaths" -> {
                    val paths = args?.firstOrNull() as? List<*> ?: emptyList<String>()
                    paths.mapNotNull { it as? String }.filter { existingPaths.contains(it) }
                }

                "toString" -> "MediaDaoHarness"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException("MediaDao.${method.name} not implemented in test harness")
            }
        }
    }

    private companion object {
        const val TEST_ALBUM_ID = 42L

        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(type: Class<T>, handler: InvocationHandler): T {
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
        }
    }
}
