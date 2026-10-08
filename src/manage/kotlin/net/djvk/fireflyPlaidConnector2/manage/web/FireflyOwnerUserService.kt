package net.djvk.fireflyPlaidConnector2.manage.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.client.RestClient

const val OWNER_AUTHORITY = "ROLE_PLAID_OWNER"

/** The fields of Firefly's `/api/v1/about/user` that the login decision uses. */
data class FireflyUser(
    val id: String?,
    val email: String?,
    val role: String?,
    val blocked: Boolean,
) {
    companion object {
        /** Firefly answers in JSON:API: the fields are under `data.attributes`. */
        fun fromJson(node: JsonNode): FireflyUser {
            val data = node.path("data")
            val attributes = data.path("attributes")
            return FireflyUser(
                id = data.path("id").asText(null),
                email = attributes.path("email").asText(null),
                role = attributes.path("role").asText(null),
                // Treat a missing flag as blocked: fail closed.
                blocked = attributes.path("blocked").let { !it.isBoolean || it.booleanValue() },
            )
        }
    }
}

/** Access requires an unblocked Firefly owner whose email is on the allow-list. */
fun isAllowed(user: FireflyUser, allowedEmails: Set<String>): Boolean =
    user.role == "owner" &&
        !user.blocked &&
        user.email != null &&
        allowedEmails.any { it.equals(user.email, ignoreCase = true) }

/**
 * Logs in with Firefly (a confidential OAuth client of Laravel Passport) and admits only
 * [isAllowed] users. The Firefly access token is used for this one call and then discarded:
 * see [DiscardingAuthorizedClientRepository].
 */
class FireflyOwnerUserService(
    private val restClient: RestClient,
    private val allowedEmails: Set<String>,
    private val mapper: ObjectMapper = ObjectMapper(),
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val uri = userRequest.clientRegistration.providerDetails.userInfoEndpoint.uri
        val body = try {
            restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${userRequest.accessToken.tokenValue}")
                .header(HttpHeaders.ACCEPT, "application/vnd.api+json, application/json")
                .retrieve()
                .body(String::class.java)
        } catch (e: Exception) {
            logger.warn("Firefly user lookup failed: {}", e.javaClass.simpleName)
            throw OAuth2AuthenticationException(OAuth2Error("invalid_user_info_response"), "Firefly user lookup failed")
        }
        val user = FireflyUser.fromJson(mapper.readTree(body ?: "{}"))
        if (!isAllowed(user, allowedEmails)) {
            logger.warn("Refused dashboard login for Firefly user {} (role={}, blocked={})", user.id, user.role, user.blocked)
            throw OAuth2AuthenticationException(OAuth2Error("access_denied"), "Not allowed")
        }
        logger.info("Dashboard login for Firefly user {}", user.id)
        return DefaultOAuth2User(
            listOf(SimpleGrantedAuthority(OWNER_AUTHORITY)),
            mapOf("email" to user.email!!, "id" to (user.id ?: "")),
            "email",
        )
    }
}
