package com.imagenext.core.network.webdav

import com.imagenext.core.model.MediaItem
import com.imagenext.core.model.MediaKind
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.ArrayDeque
import java.util.Locale
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
        data class Error(
            val message: String,
            val cause: Throwable? = null,
            val category: ErrorCategory = ErrorCategory.UNKNOWN,
            val httpStatusCode: Int? = null,
        ) : WebDavResult<Nothing> {
            val isTransient: Boolean
                get() = category == ErrorCategory.TRANSIENT
        }

        enum class ErrorCategory {
            TRANSIENT,
            AUTH,
            NOT_FOUND,
            SECURITY,
            CLIENT,
            UNKNOWN,
        }
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
            exceptionError("Server not found. Please check your connection.", e)
        } catch (e: SocketTimeoutException) {
            exceptionError("Connection timed out while discovering folders.", e)
        } catch (e: SSLException) {
            exceptionError("Secure connection failed.", e)
        } catch (e: IOException) {
            exceptionError("Failed to discover folders: ${e.message}", e)
        } catch (e: Exception) {
            exceptionError("Unexpected error during folder discovery: ${e.message}", e)
        }
    }

    /**
     * Lists media files in the given [folderPath].
     *
     * Uses a PROPFIND Depth:1 to enumerate immediate children and filters
     * for known media MIME types.
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
                    val body = response.body.string()
                    val items = parseMediaItems(body, folderPath, serverUrl, loginName)
                    WebDavResult.Success(items)
                } else {
                    httpError(
                        message = "Failed to list files (HTTP ${response.code}).",
                        statusCode = response.code,
                    )
                }
            }
        } catch (e: UnknownHostException) {
            exceptionError("Server not found.", e)
        } catch (e: SocketTimeoutException) {
            exceptionError("Connection timed out.", e)
        } catch (e: SSLException) {
            exceptionError("Secure connection failed.", e)
        } catch (e: IOException) {
            exceptionError("Failed to list media files: ${e.message}", e)
        }
    }

    /**
     * Recursively lists media files under [folderPath].
     *
     * Traverses child folders breadth-first up to [maxDepth], issuing
     * Depth:1 PROPFIND per folder and aggregating all supported media files.
     */
    fun listMediaFilesRecursively(
        serverUrl: String,
        loginName: String,
        appPassword: String,
        folderPath: String,
        maxDepth: Int = MAX_MEDIA_SCAN_DEPTH,
    ): WebDavResult<List<MediaItem>> {
        return try {
            val queue = ArrayDeque<Pair<String, Int>>()
            val visitedFolders = mutableSetOf<String>()
            val mediaItems = mutableListOf<MediaItem>()
            val scanDeadlineMs = System.currentTimeMillis() + MAX_MEDIA_SCAN_DURATION_MS

            queue.add(folderPath to 0)

            scanLoop@ while (queue.isNotEmpty() && visitedFolders.size < MAX_MEDIA_FOLDER_SCAN_COUNT) {
                if (System.currentTimeMillis() >= scanDeadlineMs) {
                    break
                }
                val (currentFolder, depth) = queue.removeFirst()
                val normalizedFolder = currentFolder.trimEnd('/').ifEmpty { "/" }
                if (!visitedFolders.add(normalizedFolder)) continue

                val webDavUrl = buildWebDavUrl(serverUrl, loginName, currentFolder)
                val request = buildPropfindRequest(webDavUrl, loginName, appPassword, depth = "1")

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.code != 207) {
                            // Root folder failure is considered fatal for this scan.
                            if (depth == 0 || response.code == 401) {
                                return httpError(
                                    message = "Failed to list files (HTTP ${response.code}) in $currentFolder.",
                                    statusCode = response.code,
                                )
                            }
                            // Child folder issues should not stall the entire sync.
                            continue@scanLoop
                        }

                        val body = response.body.string()
                        if (body.length > MAX_RESPONSE_SIZE) {
                            continue@scanLoop
                        }

                        mediaItems += parseMediaItems(
                            xml = body,
                            folderPath = currentFolder,
                            serverUrl = serverUrl,
                            loginName = loginName,
                        )

                        if (depth < maxDepth) {
                            val childFolders = parseFolders(
                                xml = body,
                                currentPath = currentFolder,
                                loginName = loginName,
                            )
                            childFolders.forEach { child ->
                                if (!shouldTraverseFolder(child.remotePath)) return@forEach
                                queue.add(child.remotePath to (depth + 1))
                            }
                        }
                    }
                } catch (e: UnknownHostException) {
                    if (depth == 0) return exceptionError("Server not found.", e)
                    continue@scanLoop
                } catch (e: SocketTimeoutException) {
                    if (depth == 0) return exceptionError("Connection timed out.", e)
                    continue@scanLoop
                } catch (e: SSLException) {
                    if (depth == 0) return exceptionError("Secure connection failed.", e)
                    continue@scanLoop
                } catch (e: IOException) {
                    if (depth == 0) return exceptionError("Failed to list media files: ${e.message}", e)
                    continue@scanLoop
                }
            }

            WebDavResult.Success(mediaItems.distinctBy { it.remotePath })
        } catch (e: UnknownHostException) {
            exceptionError("Server not found.", e)
        } catch (e: SocketTimeoutException) {
            exceptionError("Connection timed out.", e)
        } catch (e: SSLException) {
            exceptionError("Secure connection failed.", e)
        } catch (e: IOException) {
            exceptionError("Failed to list media files: ${e.message}", e)
        }
    }

    private fun shouldTraverseFolder(remotePath: String): Boolean {
        val folderName = remotePath
            .trimEnd('/')
            .substringAfterLast('/')
            .lowercase(Locale.US)
        if (folderName.isBlank()) return false
        if (folderName.startsWith(".")) return false
        return folderName !in SKIPPED_MEDIA_SCAN_FOLDERS
    }

    private fun httpError(message: String, statusCode: Int): WebDavResult.Error {
        return WebDavResult.Error(
            message = message,
            category = categorizeHttpStatus(statusCode),
            httpStatusCode = statusCode,
        )
    }

    private fun exceptionError(message: String, cause: Exception): WebDavResult.Error {
        return WebDavResult.Error(
            message = message,
            cause = cause,
            category = categorizeException(cause),
        )
    }

    private fun categorizeHttpStatus(statusCode: Int): WebDavResult.ErrorCategory {
        return when (statusCode) {
            401, 403 -> WebDavResult.ErrorCategory.AUTH
            404 -> WebDavResult.ErrorCategory.NOT_FOUND
            in 500..599 -> WebDavResult.ErrorCategory.TRANSIENT
            else -> WebDavResult.ErrorCategory.CLIENT
        }
    }

    private fun categorizeException(exception: Exception): WebDavResult.ErrorCategory {
        return when (exception) {
            is UnknownHostException,
            is SocketTimeoutException,
            is IOException,
            -> WebDavResult.ErrorCategory.TRANSIENT
            is SSLException -> WebDavResult.ErrorCategory.SECURITY
            else -> WebDavResult.ErrorCategory.UNKNOWN
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

            val body = response.body.string()
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
                            val remotePath = extractRemotePath(href, loginName)
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
     * Filters for known media MIME types.
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
                        if (inResponse && !isCollection && href != null) {
                            val remotePath = extractRemotePath(href, loginName)
                            val fileName = remotePath.substringAfterLast('/')
                            val mediaKind = resolveMediaKind(contentType, fileName)
                            if (mediaKind != MediaKind.UNKNOWN) {
                                val timestamp = parseHttpDate(lastModified)
                                val captureTimestamp = parseCaptureTimestampFromFileName(fileName)

                                items.add(
                                    MediaItem(
                                        remotePath = remotePath,
                                        fileName = fileName,
                                        mimeType = normalizeMimeType(contentType, fileName, mediaKind),
                                        size = contentLength,
                                        lastModified = timestamp,
                                        captureTimestamp = captureTimestamp,
                                        etag = etag.orEmpty().trim('"'),
                                        folderPath = folderPath,
                                    )
                                )
                            }
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

    private fun resolveMediaKind(mimeType: String?, fileName: String): MediaKind {
        return MediaKind.from(
            mimeType = mimeType,
            fileName = fileName,
        )
    }

    private fun normalizeMimeType(mimeType: String?, fileName: String, mediaKind: MediaKind): String {
        val normalized = mimeType?.trim().orEmpty()
        if (normalized.isNotBlank() && !GENERIC_MIME_TYPES.contains(normalized.lowercase(Locale.US))) {
            return normalized
        }

        return when (mediaKind) {
            MediaKind.IMAGE -> {
                val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
                IMAGE_EXTENSION_TO_MIME[extension] ?: "image/*"
            }
            MediaKind.VIDEO -> {
                val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
                VIDEO_EXTENSION_TO_MIME[extension] ?: "video/*"
            }
            MediaKind.UNKNOWN -> normalized
        }
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

    /**
     * Best-effort capture timestamp parsing from common camera filename patterns.
     * Returns null when filename does not contain a recognizable date token.
     */
    private fun parseCaptureTimestampFromFileName(fileName: String): Long? {
        val normalized = fileName.substringBeforeLast('.')

        val dateTimeToken = DATE_TIME_TOKEN_REGEX.find(normalized)?.groupValues?.get(1)
        if (dateTimeToken != null) {
            for (formatter in dateTimeFormatters) {
                try {
                    val dateTime = LocalDateTime.parse(dateTimeToken, formatter)
                    return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    // Try next formatter.
                }
            }
        }

        val dateToken = DATE_TOKEN_REGEX.find(normalized)?.groupValues?.get(1)
        if (dateToken != null) {
            for (formatter in dateOnlyFormatters) {
                try {
                    val date = LocalDate.parse(dateToken, formatter)
                    return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    // Try next formatter.
                }
            }
        }

        return null
    }

    companion object {
        /** Maximum folder depth for recursive discovery. */
        const val MAX_FOLDER_DEPTH = 3

        /** Maximum number of folders to discover to prevent unbounded results. */
        const val MAX_FOLDER_COUNT = 500

        /** Maximum XML response size to parse (2 MB). */
        const val MAX_RESPONSE_SIZE = 2 * 1024 * 1024
        const val MAX_MEDIA_SCAN_DEPTH = 2
        const val MAX_MEDIA_FOLDER_SCAN_COUNT = 120
        const val MAX_MEDIA_SCAN_DURATION_MS = 45_000L

        private val DATE_TIME_TOKEN_REGEX = Regex("(\\d{8}_\\d{6}|\\d{4}-\\d{2}-\\d{2}[ _-]\\d{2}[-:]\\d{2}[-:]\\d{2})")
        private val DATE_TOKEN_REGEX = Regex("(\\d{8}|\\d{4}-\\d{2}-\\d{2})")
        private val GENERIC_MIME_TYPES = setOf(
            "application/octet-stream",
            "binary/octet-stream",
        )
        private val IMAGE_EXTENSION_TO_MIME = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
            "webp" to "image/webp",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "avif" to "image/avif",
            "tif" to "image/tiff",
            "tiff" to "image/tiff",
            "dng" to "image/x-adobe-dng",
            "raw" to "image/x-raw",
            "arw" to "image/x-sony-arw",
            "cr2" to "image/x-canon-cr2",
            "cr3" to "image/x-canon-cr3",
            "nef" to "image/x-nikon-nef",
            "orf" to "image/x-olympus-orf",
            "rw2" to "image/x-panasonic-rw2",
        )
        private val VIDEO_EXTENSION_TO_MIME = mapOf(
            "mp4" to "video/mp4",
            "m4v" to "video/x-m4v",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
        )
        private val SKIPPED_MEDIA_SCAN_FOLDERS = setOf(
            "thumbnails",
            "@eadir",
            "cache",
            "tmp",
            "temp",
            "trash",
            "files_trashbin",
            "files_versions",
        )

        private val dateTimeFormatters = listOf(
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US),
        )

        private val dateOnlyFormatters = listOf(
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US),
            DateTimeFormatter.ISO_LOCAL_DATE,
        )
    }
}
