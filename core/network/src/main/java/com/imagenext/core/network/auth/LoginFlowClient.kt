package com.imagenext.core.network.auth

import com.imagenext.core.model.AuthSession
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Login Flow v2 manager for Nextcloud browser-based authentication.
 *
 * Flow:
 * 1. [initiate] — POST to `/index.php/login/v2` to get a login URL and poll endpoint.
 * 2. Open the `loginUrl` in the user's browser.
 * 3. [poll] — repeatedly call the poll endpoint until the user completes login or timeout.
 *
 * Reference: https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
 */
class LoginFlowClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Data returned after initiating Login Flow v2.
     *
     * @property loginUrl URL to open in the user's browser.
     * @property pollEndpoint Endpoint to poll for completion.
     * @property pollToken Token to include with poll requests.
     */
    data class LoginFlowInit(
        val loginUrl: String,
        val pollEndpoint: String,
        val pollToken: String,
    )

    /** Result wrapper for Login Flow operations. */
    sealed interface FlowResult<out T> {
        data class Success<T>(val data: T) : FlowResult<T>
        data class Error(val message: String, val cause: Throwable? = null) : FlowResult<Nothing>
        /**
         * Temporary poll failure (e.g. DNS timeout) that can be retried.
         *
         * Used only by [poll] to distinguish transient network instability from
         * terminal auth failures.
         */
        data class TransientError(val message: String, val cause: Throwable? = null) : FlowResult<Nothing>
        /** Poll returned 404 — user has not completed login yet. */
        data object Pending : FlowResult<Nothing>
    }

    /**
     * Initiates Login Flow v2 on the given server.
     *
     * @param serverUrl Normalized base URL of the Nextcloud instance.
     * @return [FlowResult.Success] with [LoginFlowInit] or [FlowResult.Error].
     */
    fun initiate(serverUrl: String): FlowResult<LoginFlowInit> {
        val url = "$serverUrl/index.php/login/v2"
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().build()) // Empty POST body as per spec
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return FlowResult.Error(
                        "Login Flow v2 is not available on this server (HTTP ${response.code}). " +
                                "You can use the manual app-password login instead."
                    )
                }

                val body = response.body?.string()
                    ?: return FlowResult.Error("Empty response from server.")

                try {
                    val json = JSONObject(body)
                    val loginUrl = json.getString("login")
                    val pollObj = json.getJSONObject("poll")
                    val pollEndpoint = pollObj.getString("endpoint")
                    val pollToken = pollObj.getString("token")

                    FlowResult.Success(
                        LoginFlowInit(
                            loginUrl = loginUrl,
                            pollEndpoint = pollEndpoint,
                            pollToken = pollToken,
                        )
                    )
                } catch (e: Exception) {
                    FlowResult.Error("Unexpected response format from server.", e)
                }
            }
        } catch (e: UnknownHostException) {
            FlowResult.Error("Server not found. Please check the URL.", e)
        } catch (e: SocketTimeoutException) {
            FlowResult.Error("Connection timed out.", e)
        } catch (e: SSLException) {
            FlowResult.Error("Secure connection failed.", e)
        } catch (e: IOException) {
            FlowResult.Error("Connection error: ${e.message}", e)
        }
    }

    /**
     * Polls the Login Flow v2 endpoint once for completion.
     *
     * @param pollEndpoint The poll endpoint URL from [LoginFlowInit].
     * @param pollToken The poll token from [LoginFlowInit].
     * @param serverUrl The normalized base URL (used to construct the [AuthSession]).
     * @return [FlowResult.Success] if login completed, [FlowResult.Pending] if not yet, or [FlowResult.Error].
     */
    fun poll(
        pollEndpoint: String,
        pollToken: String,
        serverUrl: String,
    ): FlowResult<AuthSession> {
        val formBody = FormBody.Builder()
            .add("token", pollToken)
            .build()

        val request = Request.Builder()
            .url(pollEndpoint)
            .post(formBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                            ?: return FlowResult.Error("Empty response from server.")

                        try {
                            val json = JSONObject(body)
                            val loginName = json.getString("loginName")
                            val appPassword = json.getString("appPassword")

                            FlowResult.Success(
                                AuthSession(
                                    serverUrl = serverUrl,
                                    loginName = loginName,
                                    appPassword = appPassword,
                                    userId = loginName,
                                )
                            )
                        } catch (e: Exception) {
                            FlowResult.Error("Unexpected response format.", e)
                        }
                    }
                    404 -> FlowResult.Pending
                    else -> FlowResult.Error("Poll failed (HTTP ${response.code}).")
                }
            }
        } catch (e: UnknownHostException) {
            FlowResult.TransientError("Server temporarily unreachable during poll: ${e.message}", e)
        } catch (e: SocketTimeoutException) {
            FlowResult.TransientError("Poll request timed out: ${e.message}", e)
        } catch (e: SSLException) {
            FlowResult.Error("Secure connection failed during poll.", e)
        } catch (e: IOException) {
            FlowResult.TransientError("Connection error during poll: ${e.message}", e)
        }
    }
}
