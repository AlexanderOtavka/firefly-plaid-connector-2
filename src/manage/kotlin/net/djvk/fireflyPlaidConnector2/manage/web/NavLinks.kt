package net.djvk.fireflyPlaidConnector2.manage.web

import net.djvk.fireflyPlaidConnector2.manage.MANAGE_MODE
import net.djvk.fireflyPlaidConnector2.manage.SYNC_MODE_PROPERTY
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

const val NAV_LINKS_PROPERTY = "fireflyPlaidConnector2.manage.navLinks"

/** [NAV_LINKS_PROPERTY] in the canonical form [Binder] needs; it still matches the camelCase. */
private const val NAV_LINKS_CANONICAL = "firefly-plaid-connector2.manage.nav-links"

data class NavLink(var label: String = "", var url: String = "")

/**
 * The links on the right of the header, before Log out. Templates read them as `@navLinks`,
 * so every page, error pages included, gets them without a model attribute.
 *
 * Configured as a list, for example in YAML:
 *
 *     fireflyPlaidConnector2.manage.navLinks:
 *       - { label: Back to Firefly, url: / }
 *       - { label: Grafana, url: "https://grafana.example.com/" }
 *
 * or as environment variables, `FIREFLYPLAIDCONNECTOR2_MANAGE_NAVLINKS_0_LABEL` and
 * `..._0_URL`, `..._1_LABEL`, and so on. Unset, the header has one link back to Firefly at `/`;
 * set to an empty list, it has none.
 */
@Component("navLinks")
@ConditionalOnProperty(name = [SYNC_MODE_PROPERTY], havingValue = MANAGE_MODE)
class NavLinks(environment: Environment) {
    val links: List<NavLink> = Binder.get(environment)
        .bind(NAV_LINKS_CANONICAL, Bindable.listOf(NavLink::class.java))
        .orElse(DEFAULT)
        .onEach { validate(it) }

    companion object {
        val DEFAULT = listOf(NavLink("Back to Firefly", "/"))

        /** Only web links: a `javascript:` URL in the header would run in the dashboard's origin. */
        private val allowedUrl = Regex("""^(https?://|/)""", RegexOption.IGNORE_CASE)

        private fun validate(link: NavLink) {
            require(link.label.isNotBlank()) { "$NAV_LINKS_PROPERTY: every link needs a label" }
            require(allowedUrl.containsMatchIn(link.url.trim())) {
                "$NAV_LINKS_PROPERTY: '${link.label}' must link to an http(s) URL or a path starting with /"
            }
        }
    }
}
