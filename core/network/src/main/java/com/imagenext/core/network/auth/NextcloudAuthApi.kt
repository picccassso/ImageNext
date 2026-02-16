package com.imagenext.core.network.auth

import com.imagenext.core.model.AuthSession
import com.imagenext.core.model.ServerConfig
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Auth endpoint contracts and request orchestration for Nextcloud servers.
 *
 * Handles server reachability checks and credential validation via WebDAV.
 */
class NextcloudAuthApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Result wrapper for auth operations.
     */
    sealed interface AuthResult<out T> {
        data class Success<T>(val data: T) : AuthResult<T>
        data class Error(val message: String, val cause: Throwable? = null) : AuthResult<Nothing>
    }

    /**
     * Checks that a Nextcloud server is reachable at the given URL.
     *
     * Verifies reachability by requesting `/status.php` which returns JSON on all Nextcloud instances.
     */
    fun checkServerReachability(baseUrl: String): AuthResult<ServerConfig> {
        val statusUrl = "$baseUrl/status.php"
        val request = Request.Builder()
            .url(statusUrl)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (body.contains("\"installed\"") || body.contains("\"productname\"")) {
                        AuthResult.Success(ServerConfig(baseUrl = baseUrl))
                    } else {
                        AuthResult.Error("The server does not appear to be a Nextcloud instance.")
                    }
                } else {
                    AuthResult.Error("Server returned an error (HTTP ${response.code}). Please check the URL.")
                }
            }
        } catch (e: UnknownHostException) {
            AuthResult.Error("Server not found. Please check the URL and your internet connection.", e)
        } catch (e: SocketTimeoutException) {
            AuthResult.Error("Connection timed out. The server may be unreachable.", e)
        } catch (e: SSLException) {
            AuthResult.Error(
                "Secure connection failed. The server may use a self-signed certificate.", e
            )
        } catch (e: IOException) {
            AuthResult.Error("Could not connect to the server: ${e.message}", e)
        }
    }

    /**
     * Validates credentials by performing a WebDAV PROPFIND against the user's files endpoint.
     *
     * This confirms the server, username, and app-password combination is valid.
     * The main-password will also technically "work" here, but the UI layer
     * must guide users toward app-passwords only.
     */
    fun validateCredentials(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ): AuthResult<AuthSession> {
        val webDavUrl = "$serverUrl/remote.php/dav/files/$loginName/"
        val request = Request.Builder()
            .url(webDavUrl)
            .method("PROPFIND", null)
            .header("Authorization", Credentials.basic(loginName, appPassword))
            .header("Depth", "0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    207 -> AuthResult.Success(
                        AuthSession(
                            serverUrl = serverUrl,
                            loginName = loginName,
                            appPassword = appPassword,
                            userId = loginName,
                        )
                    )
                    401 -> AuthResult.Error(
                        "Invalid credentials. If you use two-factor authentication, " +
                                "please generate an app password in your Nextcloud security settings."
                    )
                    403 -> AuthResult.Error(
                        "Access denied. Your account or app password may have been revoked."
                    )
                    404 -> AuthResult.Error(
                        "WebDAV endpoint not found. Please verify the server URL."
                    )
                    else -> AuthResult.Error(
                        "Authentication failed (HTTP ${response.code}). Please try again."
                    )
                }
            }
        } catch (e: UnknownHostException) {
            AuthResult.Error("Server not found. Please check the URL and your internet connection.", e)
        } catch (e: SocketTimeoutException) {
            AuthResult.Error("Connection timed out. The server may be unreachable.", e)
        } catch (e: SSLException) {
            AuthResult.Error("Secure connection failed. The server may use a self-signed certificate.", e)
        } catch (e: IOException) {
            AuthResult.Error("Connection error: ${e.message}", e)
        }
    }
}
