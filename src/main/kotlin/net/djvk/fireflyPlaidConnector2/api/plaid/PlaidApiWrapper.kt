package net.djvk.fireflyPlaidConnector2.api.plaid

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.djvk.fireflyPlaidConnector2.api.plaid.apis.PlaidApi
import net.djvk.fireflyPlaidConnector2.api.plaid.infrastructure.ApiClient
import net.djvk.fireflyPlaidConnector2.config.SecretValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import kotlin.math.pow
import kotlin.time.Duration.Companion.minutes

typealias PlaidTransactionId = String

const val clientIdHeader = "PLAID-CLIENT-ID"
const val secretHeader = "PLAID-SECRET"

/**
 * A wrapper for Plaid API calls that provides additional services:
 *  - rate limiting
 *  - retry logic
 *  - error handling
 */
@Component
class PlaidApiWrapper(
    @Value("\${fireflyPlaidConnector2.plaid.url}")
    private val baseUrl: String,
    @Value("\${fireflyPlaidConnector2.plaid.maxRetries:3}")
    private val maxRetries: Int,
    @Value("\${fireflyPlaidConnector2.plaid.retryBaseDelayMillis:1000}")
    private val retryBaseDelayMillis: Long = 1000,
    @Value("\${fireflyPlaidConnector2.plaid.clientId:}")
    private val plaidClientId: String = "",
    @Value("\${fireflyPlaidConnector2.plaid.secret:}")
    private val plaidSecret: String = "",
    @Value("\${fireflyPlaidConnector2.plaid.clientIdFile:}")
    private val plaidClientIdFile: String = "",
    @Value("\${fireflyPlaidConnector2.plaid.secretFile:}")
    private val plaidSecretFile: String = "",
    httpClientEngine: HttpClientEngine? = null,
    httpClientConfig: ((HttpClientConfig<*>) -> Unit)? = null,
) {
    private val plaidApi = PlaidApi(baseUrl, httpClientEngine, httpClientConfig) {
        ApiClient.JSON_DEFAULT.invoke(this)
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
        // Omit unset fields rather than send them as null. Plaid treats an absent field as
        // unset but validates a present one, so `"access_tokens": null` on /link/token/create
        // fails with INVALID_FIELD ("access_tokens must be an array of strings").
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        require(maxRetries >= 1) {
            "fireflyPlaidConnector2.plaid.maxRetries must be at least 1"
        }
        plaidApi.setApiKey(
            SecretValue.resolve(
                plaidClientId,
                plaidClientIdFile,
                "fireflyPlaidConnector2.plaid.clientId",
            ),
            clientIdHeader,
        )
        plaidApi.setApiKey(
            SecretValue.resolve(
                plaidSecret,
                plaidSecretFile,
                "fireflyPlaidConnector2.plaid.secret",
            ),
            secretHeader,
        )
    }

    /**
     * Executes a request to the Plaid API
     *
     * @param logString String to include in log messages
     */
    suspend fun <T> executeRequest(
        request: suspend (PlaidApi) -> T,
        logString: String,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return request(plaidApi)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                if (e.response.status != HttpStatusCode.TooManyRequests) {
                    throw e
                }
                if (attempt >= maxRetries) {
                    throw RuntimeException(
                        "Plaid API call $logString failed after $maxRetries attempts",
                        e,
                    )
                }
                val retryAfterMillis = e.response.headers[HttpHeaders.RetryAfter]
                    ?.toLongOrNull()
                    ?.times(1000)
                val waitMillis = retryAfterMillis ?: getRetryDelayMillis(attempt)
                logger.warn(
                    "Plaid rate limited $logString on attempt $attempt of $maxRetries; retrying after ${waitMillis}ms",
                )
                delay(waitMillis)
            } catch (e: Throwable) {
                if (!isRetryable(e) || attempt >= maxRetries) {
                    if (!isRetryable(e)) {
                        throw e
                    }
                    throw RuntimeException(
                        "Plaid API call $logString failed after $maxRetries attempts",
                        e,
                    )
                }
                val waitMillis = getRetryDelayMillis(attempt)
                logger.warn(
                    "Transient Plaid error calling $logString on attempt $attempt of $maxRetries; " +
                            "retrying after ${waitMillis}ms",
                    e,
                )
                delay(waitMillis)
            }
            attempt++
        }
    }

    private fun isRetryable(error: Throwable): Boolean {
        return error is ServerResponseException ||
                error is HttpRequestTimeoutException ||
                error is IOException
    }

    private fun getRetryDelayMillis(failedAttempt: Int): Long {
        return (retryBaseDelayMillis * 2.0.pow(failedAttempt - 1))
            .toLong()
            .coerceAtMost(1.minutes.inWholeMilliseconds)
    }
}
