package com.imagenext.core.network.webdav

import com.imagenext.core.model.SelectedFolder
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class WebDavClientTest {

    private val client = WebDavClient()

    // --- parseFolders tests ---

    @Test
    fun `parseFolders extracts child folders and skips self reference`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                            <d:displayname>root</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                            <d:displayname>Photos</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Documents/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                            <d:displayname>Documents</d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val folders = client.parseFolders(xml, "/", "testuser")

        assertEquals(2, folders.size)
        assertEquals("/Photos/", folders[0].remotePath)
        assertEquals("Photos", folders[0].displayName)
        assertEquals("/Documents/", folders[1].remotePath)
        assertEquals("Documents", folders[1].displayName)
    }

    @Test
    fun `parseFolders returns empty list for non-collection items`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/file.jpg</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>image/jpeg</d:getcontenttype>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val folders = client.parseFolders(xml, "/", "testuser")
        assertTrue(folders.isEmpty())
    }

    @Test
    fun `parseFolders uses filename as fallback displayname`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Camera/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                            <d:displayname></d:displayname>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val folders = client.parseFolders(xml, "/", "testuser")
        assertEquals(1, folders.size)
        assertEquals("Camera", folders[0].displayName)
    }

    // --- parseMediaItems tests ---

    @Test
    fun `parseMediaItems extracts image files`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/sunset.jpg</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>image/jpeg</d:getcontenttype>
                            <d:getcontentlength>1024000</d:getcontentlength>
                            <d:getetag>"abc123"</d:getetag>
                        </d:prop>
                    </d:propstat>
                </d:response>
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/notes.txt</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>text/plain</d:getcontenttype>
                            <d:getcontentlength>256</d:getcontentlength>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = client.parseMediaItems(xml, "/Photos/", "https://cloud.example.com", "testuser")

        assertEquals(1, items.size)
        assertEquals("sunset.jpg", items[0].fileName)
        assertEquals("image/jpeg", items[0].mimeType)
        assertEquals(1024000, items[0].size)
        assertEquals("abc123", items[0].etag)
        assertEquals("/Photos/", items[0].folderPath)
    }

    @Test
    fun `parseMediaItems filters out collections`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype><d:collection/></d:resourcetype>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = client.parseMediaItems(xml, "/Photos/", "https://cloud.example.com", "testuser")
        assertTrue(items.isEmpty())
    }

    @Test
    fun `parseMediaItems extracts capture timestamp from camera style filename`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/20260114_123626_sample.jpg</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>image/jpeg</d:getcontenttype>
                            <d:getcontentlength>2048</d:getcontentlength>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = client.parseMediaItems(xml, "/Photos/", "https://cloud.example.com", "testuser")
        assertEquals(1, items.size)
        assertNotNull(items[0].captureTimestamp)
    }

    @Test
    fun `parseMediaItems keeps jpg when content type is generic`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/new_upload.JPG</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>application/octet-stream</d:getcontenttype>
                            <d:getcontentlength>3072</d:getcontentlength>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = client.parseMediaItems(xml, "/Photos/", "https://cloud.example.com", "testuser")
        assertEquals(1, items.size)
        assertEquals("new_upload.JPG", items[0].fileName)
        assertEquals("image/jpeg", items[0].mimeType)
    }

    @Test
    fun `parseMediaItems includes video when mime type is video slash`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/clip.mp4</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>video/mp4</d:getcontenttype>
                            <d:getcontentlength>4096</d:getcontentlength>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = client.parseMediaItems(xml, "/Photos/", "https://cloud.example.com", "testuser")
        assertEquals(1, items.size)
        assertEquals("clip.mp4", items[0].fileName)
        assertEquals("video/mp4", items[0].mimeType)
    }

    @Test
    fun `parseMediaItems includes video by extension when content type is generic`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/holiday.MOV</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>application/octet-stream</d:getcontenttype>
                            <d:getcontentlength>5096</d:getcontentlength>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = client.parseMediaItems(xml, "/Photos/", "https://cloud.example.com", "testuser")
        assertEquals(1, items.size)
        assertEquals("holiday.MOV", items[0].fileName)
        assertEquals("video/quicktime", items[0].mimeType)
    }

    @Test
    fun `parseMediaItems excludes unsupported non-media files`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
                <d:response>
                    <d:href>/remote.php/dav/files/testuser/Photos/report.pdf</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype/>
                            <d:getcontenttype>application/pdf</d:getcontenttype>
                            <d:getcontentlength>1200</d:getcontentlength>
                        </d:prop>
                    </d:propstat>
                </d:response>
            </d:multistatus>
        """.trimIndent()

        val items = client.parseMediaItems(xml, "/Photos/", "https://cloud.example.com", "testuser")
        assertTrue(items.isEmpty())
    }

    // --- Safety limits ---

    @Test
    fun `MAX_FOLDER_DEPTH is 3`() {
        assertEquals(3, WebDavClient.MAX_FOLDER_DEPTH)
    }

    @Test
    fun `MAX_FOLDER_COUNT is 500`() {
        assertEquals(500, WebDavClient.MAX_FOLDER_COUNT)
    }

    @Test
    fun `MAX_RESPONSE_SIZE is 2MB`() {
        assertEquals(2 * 1024 * 1024, WebDavClient.MAX_RESPONSE_SIZE)
    }

    @Test
    fun `ensureFolderPath treats existing path as success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(405))
        server.start()
        try {
            val client = WebDavClient(OkHttpClient())
            val result = client.ensureFolderPath(
                serverUrl = server.url("/").toString().trimEnd('/'),
                loginName = "testuser",
                appPassword = "app-pass",
                folderPath = "/Photos/2026",
            )
            assertTrue(result is WebDavClient.WebDavResult.Success)
            assertEquals("MKCOL", server.takeRequest().method)
            assertEquals("MKCOL", server.takeRequest().method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `headFile maps 404 to null success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val client = WebDavClient(OkHttpClient())
            val result = client.headFile(
                serverUrl = server.url("/").toString().trimEnd('/'),
                loginName = "testuser",
                appPassword = "app-pass",
                remotePath = "/Photos/a.jpg",
            )
            assertTrue(result is WebDavClient.WebDavResult.Success)
            val data = (result as WebDavClient.WebDavResult.Success).data
            assertNull(data)
            assertEquals("HEAD", server.takeRequest().method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `putFile sends PUT and returns success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()
        try {
            val client = WebDavClient(OkHttpClient())
            val result = client.putFile(
                serverUrl = server.url("/").toString().trimEnd('/'),
                loginName = "testuser",
                appPassword = "app-pass",
                remotePath = "/Photos/new.jpg",
                contentType = "image/jpeg",
                contentLength = 4L,
                inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
            )
            assertTrue(result is WebDavClient.WebDavResult.Success)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(4, request.bodySize)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `deleteFile treats 404 as success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val client = WebDavClient(OkHttpClient())
            val result = client.deleteFile(
                serverUrl = server.url("/").toString().trimEnd('/'),
                loginName = "testuser",
                appPassword = "app-pass",
                remotePath = "/Photos/old.jpg",
            )
            assertTrue(result is WebDavClient.WebDavResult.Success)
            assertEquals("DELETE", server.takeRequest().method)
        } finally {
            server.shutdown()
        }
    }
}
