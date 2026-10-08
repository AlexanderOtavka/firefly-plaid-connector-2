package net.djvk.fireflyPlaidConnector2.manage.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import net.djvk.fireflyPlaidConnector2.config.SecretValue
import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.web.client.RestClient
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant

const val REGISTRATION_ID = "firefly"

/** Paths reachable without a login. Metrics carry no sensitive content. */
val PUBLIC_PATHS = arrayOf("/-/metrics", "/-/healthz", "/assets/**", "/error", "/login-failed", "/logged-out")

private const val MISDIRECTED_REQUEST = 421

/**
 * Login with Firefly, on top of the private network the dashboard is served on.
 *
 * - Authorization goes through the browser to Firefly's public URL; the code exchange and the
 *   user lookup go to Firefly's in-cluster Service with the client secret.
 * - CSRF tokens live in the server session ([HttpSessionCsrfTokenRepository]) because Laravel
 *   already owns the `XSRF-TOKEN` cookie at `/` on this shared origin.
 * - The session cookie (`PLAIDSESSION`, `Path=/plaid`, `SameSite=Lax`) is configured in
 *   plaid-manager-defaults.properties. Sessions end after 30 minutes idle or 8 hours total,
 *   because logging out of Firefly does not log out of the dashboard.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class SecurityConfiguration(
    @Value("\${fireflyPlaidConnector2.manage.publicBaseUrl:}")
    private val publicBaseUrl: String,
    @Value("\${fireflyPlaidConnector2.manage.oauth.internalBaseUrl:http://firefly.firefly.svc.cluster.local}")
    private val internalBaseUrl: String,
    @Value("\${fireflyPlaidConnector2.manage.oauth.clientId:}")
    private val clientId: String,
    @Value("\${fireflyPlaidConnector2.manage.oauth.clientSecret:}")
    private val clientSecret: String,
    @Value("\${fireflyPlaidConnector2.manage.oauth.clientSecretFile:}")
    private val clientSecretFile: String,
    @Value("\${fireflyPlaidConnector2.manage.allowedEmails:}")
    private val allowedEmails: String,
    @Value("\${fireflyPlaidConnector2.manage.allowedHosts:}")
    private val allowedHosts: String,
) {
    // No defaults for where the dashboard lives or who may use it: a missing setting stops
    // startup rather than leaving the login open to some other host or account.
    init {
        require(publicBaseUrl.isNotBlank()) { "fireflyPlaidConnector2.manage.publicBaseUrl is required in manage mode" }
        require(allowedHosts.isNotBlank()) { "fireflyPlaidConnector2.manage.allowedHosts is required in manage mode" }
        require(allowedEmails.isNotBlank()) { "fireflyPlaidConnector2.manage.allowedEmails is required in manage mode" }
    }

    private val public = publicBaseUrl.trimEnd('/')
    private val internal = internalBaseUrl.trimEnd('/')

    @Bean
    fun clientRegistrationRepository(): ClientRegistrationRepository {
        require(clientId.isNotBlank()) { "fireflyPlaidConnector2.manage.oauth.clientId is required in manage mode" }
        val registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
            .clientName("Firefly III")
            .clientId(clientId)
            .clientSecret(SecretValue.resolve(clientSecret, clientSecretFile, "fireflyPlaidConnector2.manage.oauth.clientSecret"))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("$public/plaid/login/oauth2/code/$REGISTRATION_ID")
            .authorizationUri("$public/oauth/authorize")
            .tokenUri("$internal/oauth/token")
            .userInfoUri("$internal/api/v1/about/user")
            .userNameAttributeName("email")
            .build()
        return InMemoryClientRegistrationRepository(registration)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val userService = FireflyOwnerUserService(
            RestClient.create(),
            allowedEmails.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
        http
            .authorizeHttpRequests {
                it.requestMatchers(*PUBLIC_PATHS).permitAll()
                it.anyRequest().hasAuthority(OWNER_AUTHORITY)
            }
            .oauth2Login {
                it.userInfoEndpoint { endpoint -> endpoint.userService(userService) }
                it.authorizedClientRepository(DiscardingAuthorizedClientRepository())
                it.failureUrl("/login-failed")
            }
            .exceptionHandling {
                // API calls get a 401 rather than a redirect to Firefly.
                it.defaultAuthenticationEntryPointFor(
                    HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    AntPathRequestMatcher("/api/**"),
                )
                it.defaultAuthenticationEntryPointFor(
                    LoginUrlAuthenticationEntryPoint("/oauth2/authorization/$REGISTRATION_ID"),
                    AntPathRequestMatcher("/**"),
                )
            }
            .csrf { it.csrfTokenRepository(HttpSessionCsrfTokenRepository()) }
            .sessionManagement { it.sessionFixation { fixation -> fixation.changeSessionId() } }
            .logout { it.logoutUrl("/logout").logoutSuccessUrl("/logged-out").permitAll() }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN) }
                headers.frameOptions { it.deny() }
            }
            .addFilterBefore(
                HostAllowListFilter(allowedHosts.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()),
                SecurityContextHolderFilter::class.java,
            )
            .addFilterBefore(AbsoluteSessionTimeoutFilter(ABSOLUTE_SESSION_LIMIT), SecurityContextHolderFilter::class.java)
        return http.build()
    }

    companion object {
        val ABSOLUTE_SESSION_LIMIT: Duration = Duration.ofHours(8)

        /** Self, plus Plaid Link (script and iframe from cdn.plaid.com, API calls to Plaid). */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; " +
                "script-src 'self' https://cdn.plaid.com; " +
                "frame-src https://cdn.plaid.com; " +
                "connect-src 'self' https://production.plaid.com; " +
                "img-src 'self' data:; " +
                "style-src 'self' 'unsafe-inline'; " +
                "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
    }
}

/**
 * Drops the Firefly authorized client after login: the dashboard only needs Firefly to say
 * who the user is, never to act as them, so the token is not kept in the session.
 */
class DiscardingAuthorizedClientRepository : OAuth2AuthorizedClientRepository {
    override fun <T : OAuth2AuthorizedClient?> loadAuthorizedClient(
        clientRegistrationId: String?,
        principal: Authentication?,
        request: HttpServletRequest?,
    ): T? = null

    override fun saveAuthorizedClient(
        authorizedClient: OAuth2AuthorizedClient?,
        principal: Authentication?,
        request: HttpServletRequest?,
        response: HttpServletResponse?,
    ) {
    }

    override fun removeAuthorizedClient(
        clientRegistrationId: String?,
        principal: Authentication?,
        request: HttpServletRequest?,
        response: HttpServletResponse?,
    ) {
    }
}

/**
 * Rejects requests whose Host is not the dashboard's public name, so the app cannot be
 * driven through some other name that routes to it. Probes and metrics are exempt: kubelet
 * probes use the pod IP.
 */
class HostAllowListFilter(private val allowedHosts: Set<String>) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return path == "/-/healthz" || path == "/-/metrics"
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (request.serverName.lowercase() !in allowedHosts) {
            response.sendError(MISDIRECTED_REQUEST)
            return
        }
        filterChain.doFilter(request, response)
    }
}

/** Ends a session this long after it was created, however active it has been. */
class AbsoluteSessionTimeoutFilter(private val limit: Duration) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val session = request.getSession(false)
        if (session != null && Instant.ofEpochMilli(session.creationTime).plus(limit).isBefore(Instant.now())) {
            session.invalidate()
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
        filterChain.doFilter(request, response)
    }
}
