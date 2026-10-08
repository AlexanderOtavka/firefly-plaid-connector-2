package net.djvk.fireflyPlaidConnector2.api

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertFailsWith

internal class ApiConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(ApiConfiguration::class.java)

    private fun client(config: (HttpClientConfig<*>) -> Unit, responseDelayMillis: Long) =
        HttpClient(MockEngine { delay(responseDelayMillis); respondOk("ok") }, config)

    @Suppress("UNCHECKED_CAST")
    private fun clientConfigFrom(context: org.springframework.context.ApplicationContext) =
        context.getBean("getClientConfig") as (HttpClientConfig<*>) -> Unit

    @Test
    fun `request timeout comes from the property`() {
        runner.withPropertyValues("fireflyPlaidConnector2.http.requestTimeoutMillis=100").run { context ->
            val config = clientConfigFrom(context)
            runBlocking {
                assertFailsWith<HttpRequestTimeoutException> { client(config, 2000).get("http://test/") }
                assertEquals("ok", client(config, 10).get("http://test/").bodyAsText())
            }
        }
    }

    @Test
    fun `default request timeout lets a slow response finish`() {
        runner.run { context ->
            val config = clientConfigFrom(context)
            runBlocking {
                assertEquals("ok", client(config, 2000).get("http://test/").bodyAsText())
            }
        }
    }
}
