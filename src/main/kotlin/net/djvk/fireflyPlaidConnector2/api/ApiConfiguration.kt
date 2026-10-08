package net.djvk.fireflyPlaidConnector2.api

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("!test")
@Configuration
class ApiConfiguration {
    @Bean
    fun getEngine(): HttpClientEngine {
        return CIO.create()
    }

    @Bean
    fun getClientConfig(
        @Value("\${fireflyPlaidConnector2.http.requestTimeoutMillis:600000}") requestTimeoutMillis: Long = 600000,
    ): ((HttpClientConfig<*>) -> Unit) {
        return {
            it.expectSuccess = true
            it.install(HttpTimeout) {
                /**
                 * Local change: was a fixed 60 s, sized for Plaid's /accounts/balance/get. Firefly III
                 *  recalculates the running balance of every later transaction in the account on each
                 *  back-dated insert, one commit per row, so a backfill insert can take minutes on slow
                 *  storage. One timeout ends a backfill Job, so the default is 10 minutes.
                 */
                this.requestTimeoutMillis = requestTimeoutMillis
            }
//            it.install(Logging) {
//                level = LogLevel.ALL
//            }
        }
    }

//    @Bean
//    fun getJsonBlock(): ObjectMapper.() -> Unit {
//        return {}
//    }
}