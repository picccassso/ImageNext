package com.imagenext.core.network.webdav

import com.imagenext.core.model.MediaItem
import com.imagenext.core.model.SelectedFolder
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * WebDAV operations for folder discovery and metadata retrieval.
 *
 * Uses PROPFIND requests against the Nextcloud WebDAV endpoint to enumerate
 * folders and list media files. Includes depth guards to prevent unbounded
 * tree traversal on malformed or extremely deep directory structures.
 */
class WebDavClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /** Result type for WebDAV operations. */
    sealed interface WebDavResult<out T> {
        data class Success<T>(val data: T) : WebDavResult<T>
        data class Error(val message: String, val cause: Throwable? = null) : WebDavResult<Nothing>
    }

    /**
     * Discovers folders under [rootPath] on the Nextcloud server.
     *
     * Recursively traverses the folder tree up to [maxDepth] levels deep
     * to prevent excessive traversal on large or malformed trees.
     *
     * @param serverUrl Base server URL (e.g., "https://cloud.example.com").
     * @param loginName The authenticated login name.
     * @param appPassword The app-specific password.
     * @param rootPath The starting path (default: "/" for user root).
     * @param maxDepth Maximum recursion depth (default: 3).
     * @return A flat list of discovered folders.
     */
    fun discoverFolders(
        serverUrl: String,
        loginName: String,
        appPassword: String,
        rootPath: String = "/",
        maxDepth: Int = MAX_FOLDER_DEPTH,
    ): WebDavResult<List<SelectedFolder>> {
        return try {
            val folders = mutableListOf<SelectedFolder>()
            discoverFoldersRecursive(
                serverUrl = serverUrl,
                loginName = loginName,
                appPassword = appPassword,
                currentPath = rootPath,
                currentDepth = 0,
                maxDepth = maxDepth,
                accumulator = folders,
            )
            WebDavResult.Success(folders)
        } catch (e: UnknownHostException) {
            WebDavResult.Error("Server not found. Please check your connection.", e)
        } catch (e: SocketTimeoutException) {
            WebDavResult.Error("Connection timed out while discovering folders.", e)
        } catch (e: SSLException) {
            WebDavResult.Error("Secure connection failed.", e)
        } catch (e: IOException) {
            WebDavResult.Error("Failed to discover folders: ${e.message}", e)
        } catch (e: Exception) {
            WebDavResult.Error("Unexpected error during folder discovery: ${e.message}", e)
        }
    }

    /**
     * Lists media files (images) in the given [folderPath].
     *
     * Uses a PROPFIND Depth:1 to enumerate immediate children and filters
     * for known image MIME types.
     */
    fun listMediaFiles(
        serverUrl: String,
        loginName: String,
        appPassword: String,
        folderPath: String,
    ): WebDavResult<List<MediaItem>> {
        val webDavUrl = buildWebDavUrl(serverUrl, loginName, folderPath)
        val request = buildPropfindRequest(webDavUrl, loginName, appPassword, depth = "1")

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 207) {
                    val body = response.body?.string().orEmpty()
                    val items = parseMediaItems(body, folderPath, serverUrl, loginName)
                    WebDavResult.Success(items)
                } else {
                    WebDavResult.Error("Failed to list files (HTTP ${response.code}).")
                }
            }
        } catch (e: UnknownHostException) {
            WebDavResult.Error("Server not found.", e)
        } catch (e: SocketTimeoutException) {
            WebDavResult.Error("Connection timed out.", e)
        } catch (e: SSLException) {
            WebDavResult.Error("Secure connection failed.", e)
        } catch (e: IOException) {
            WebDavResult.Error("Failed to list media files: ${e.message}", e)
        }
    }

    // -- Internal helpers --

    private fun discoverFoldersRecursive(
        serverUrl: String,
        loginName: String,
        appPassword: String,
        currentPath: String,
        currentDepth: Int,
        maxDepth: Int,
        accumulator: MutableList<SelectedFolder>,
    ) {
        if (currentDepth >= maxDepth) return
        if (accumulator.size >= MAX_FOLDER_COUNT) return

        val webDavUrl = buildWebDavUrl(serverUrl, loginName, currentPath)
        val request = buildPropfindRequest(webDavUrl, loginName, appPassword, depth = "1")

        client.newCall(request).execute().use { response ->
            if (response.code != 207) return

            val body = response.body?.string().orEmpty()
            if (body.length > MAX_RESPONSE_SIZE) return

            val childFolders = parseFolders(body, currentPath, loginName)

            for (folder in childFolders) {
                if (accumulator.size >= MAX_FOLDER_COUNT) break
                accumulator.add(folder)

                discoverFoldersRecursive(
                    serverUrl = serverUrl,
                    loginName = loginName,
                    appPassword = appPassword,
                    currentPath = folder.remotePath,
                    currentDepth = currentDepth + 1,
                    maxDepth = maxDepth,
                    accumulator = accumulator,
                )
            }
        }
    }

    private fun buildWebDavUrl(serverUrl: String, loginName: String, path: String): String {
        val cleanPath = path.trimStart('/')
        return "$serverUrl/remote.php/dav/files/$loginName/$cleanPath"
    }

    private fun buildPropfindRequest(
        url: String,
        loginName: String,
        appPassword: String,
        depth: String,
    ): Request {
        val propfindBody = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                <d:prop>
                    <d:resourcetype/>
                    <d:getcontenttype/>
                    <d:getcontentlength/>
                    <d:getlastmodified/>
                    <d:getetag/>
                    <d:displayname/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        return Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
            .header("Authorization", Credentials.basic(loginName, appPassword))
            .header("Depth", depth)
            .build()
    }

    /**
     * Parses WebDAV multistatus XML to extract child folders.
     * Skips the current directory entry (self-reference).
     */
    internal fun parseFolders(
        xml: String,
        currentPath: String,
        loginName: String,
    ): List<SelectedFolder> {
        val folders = mutableListOf<SelectedFolder>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var inResponse = false
        var href: String? = null
        var isCollection = false
        var displayName: String? = null
        var currentTag = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "response", "d:response" -> {
                            inResponse = true
                            href = null
                            isCollection = false
                            displayName = null
                        }
                        "collection", "d:collection" -> {
                            if (inResponse) isCollection = true
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inResponse) {
                        when (currentTag) {
                            "href", "d:href" -> href = parser.text?.trim()
                            "displayname", "d:displayname" -> displayName = parser.text?.trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "response" || parser.name == "d:response") {
                        if (inResponse && isCollection && href != null) {
                            val remotePath = extractRemotePath(href!!, loginName)
                            val normalizedCurrent = currentPath.trimEnd('/')
                            val normalizedRemote = remotePath.trimEnd('/')

                            // Skip self-reference
                            if (normalizedRemote != normalizedCurrent && normalizedRemote.isNotEmpty()) {
                                val name = displayName?.takeIf { it.isNotBlank() }
                                    ?: remotePath.trimEnd('/').substringAfterLast('/')
                                folders.add(SelectedFolder(remotePath = remotePath, displayName = name))
                            }
                        }
                        inResponse = false
                    }
                    currentTag = ""
                }
            }
            parser.next()
        }

        return folders
    }

    /**
     * Parses WebDAV multistatus XML to extract media file items.
     * Filters for known image MIME types.
     */
    internal fun parseMediaItems(
        xml: String,
        folderPath: String,
        serverUrl: String,
        loginName: String,
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var inResponse = false
        var href: String? = null
        var isCollection = false
        var contentType: String? = null
        var contentLength: Long = 0
        var lastModified: String? = null
        var etag: String? = null
        var currentTag = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "response", "d:response" -> {
                            inResponse = true
                            href = null
                            isCollection = false
                            contentType = null
                            contentLength = 0
                            lastModified = null
                            etag = null
                        }
                        "collection", "d:collection" -> {
                            if (inResponse) isCollection = true
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inResponse) {
                        val text = parser.text?.trim().orEmpty()
                        when (currentTag) {
                            "href", "d:href" -> href = text
                            "getcontenttype", "d:getcontenttype" -> contentType = text
                            "getcontentlength", "d:getcontentlength" -> {
                                contentLength = text.toLongOrNull() ?: 0
                            }
                            "getlastmodified", "d:getlastmodified" -> lastModified = text
                            "getetag", "d:getetag" -> etag = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "response" || parser.name == "d:response") {
                        if (inResponse && !isCollection && href != null && isImageMimeType(contentType)) {
                            val remotePath = extractRemotePath(href!!, loginName)
                            val fileName = remotePath.substringAfterLast('/')
                            val timestamp = parseHttpDate(lastModified)

                            items.add(
                                MediaItem(
                                    remotePath = remotePath,
                                    fileName = fileName,
                                    mimeType = contentType.orEmpty(),
                                    size = contentLength,
                                    lastModified = timestamp,
                                    etag = etag.orEmpty().trim('"'),
                                    folderPath = folderPath,
                                )
                            )
                        }
                        inResponse = false
                    }
                    currentTag = ""
                }
            }
            parser.next()
        }

        return items
    }

    /**
     * Extracts the user-relative remote path from a WebDAV href.
     * Example: "/remote.php/dav/files/user/Photos/" → "/Photos/"
     */
    private fun extractRemotePath(href: String, loginName: String): String {
        val prefix = "/remote.php/dav/files/$loginName"
        val decoded = java.net.URLDecoder.decode(href, "UTF-8")
        return if (decoded.startsWith(prefix)) {
            decoded.removePrefix(prefix).ifEmpty { "/" }
        } else {
            decoded
        }
    }

    private fun isImageMimeType(mimeType: String?): Boolean {
        if (mimeType == null) return false
        return mimeType.startsWith("image/")
    }

    /**
     * Best-effort HTTP date parsing.
     * Returns epoch millis or 0 if parsing fails.
     */
    private fun parseHttpDate(dateString: String?): Long {
        if (dateString.isNullOrBlank()) return 0
        return try {
            @Suppress("DEPRECATION")
            java.util.Date.parse(dateString)
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        /** Maximum folder depth for recursive discovery. */
        const val MAX_FOLDER_DEPTH = 3

        /** Maximum number of folders to discover to prevent unbounded results. */
        const val MAX_FOLDER_COUNT = 500

        /** Maximum XML response size to parse (2 MB). */
        const val MAX_RESPONSE_SIZE = 2 * 1024 * 1024
    }
}
