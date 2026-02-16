package com.imagenext.core.network.auth

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class LoginFlowClientTest {

    @Test
    fun `poll returns transient error on unknown host exception`() {
        val client = failingPollClient(UnknownHostException("dns error"))

        val result = client.poll(
            pollEndpoint = "https://cloud.example.com/poll",
            pollToken = "test-token",
            serverUrl = "https://cloud.example.com",
        )

        assertTrue(result is LoginFlowClient.FlowResult.TransientError)
        val transient = result as LoginFlowClient.FlowResult.TransientError
        assertTrue(transient.cause is UnknownHostException)
    }

    @Test
    fun `poll returns transient error on socket timeout exception`() {
        val client = failingPollClient(SocketTimeoutException("timeout"))

        val result = client.poll(
            pollEndpoint = "https://cloud.example.com/poll",
            pollToken = "test-token",
            serverUrl = "https://cloud.example.com",
        )

        assertTrue(result is LoginFlowClient.FlowResult.TransientError)
        val transient = result as LoginFlowClient.FlowResult.TransientError
        assertTrue(transient.cause is SocketTimeoutException)
    }

    @Test
    fun `poll returns terminal error on ssl exception`() {
        val client = failingPollClient(SSLException("handshake failed"))

        val result = client.poll(
            pollEndpoint = "https://cloud.example.com/poll",
            pollToken = "test-token",
            serverUrl = "https://cloud.example.com",
        )

        assertTrue(result is LoginFlowClient.FlowResult.Error)
        val error = result as LoginFlowClient.FlowResult.Error
        assertTrue(error.cause is SSLException)
        assertTrue(error.message.contains("Secure connection failed during poll"))
    }

    private fun failingPollClient(exception: Exception): LoginFlowClient {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw exception }
            .build()
        return LoginFlowClient(client)
    }
}
