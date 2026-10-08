package net.djvk.fireflyPlaidConnector2.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Controls translation of Plaid personal finance categories into real Firefly categories.
 *
 * This is independent of the tag-based categorization, which only records the Plaid category on the
 * transaction and leaves it to Firefly's rule engine to turn that into a category. Enabling this
 * sets the Firefly category directly instead.
 */
@ConfigurationProperties(prefix = "firefly-plaid-connector2.categorization.firefly")
data class FireflyCategoryConfig(
    val enable: Boolean = false,
    /**
     * Overrides the category name used for a Plaid personal finance category.
     *
     * Keys are either a full detailed value (`food-and-drink-groceries`) or a primary value
     * (`food-and-drink`); a detailed key wins over a primary key. Keys are matched ignoring case
     * and treating `-` and `_` as equivalent, because Spring only binds map keys verbatim when
     * they are bracketed, and `FOOD_AND_DRINK_GROCERIES` in YAML would otherwise not match.
     */
    val overrides: Map<String, String> = emptyMap(),
) {
    private val normalizedOverrides = overrides.entries.associate { (key, value) ->
        normalizeKey(key) to value
    }

    /**
     * @param personalFinanceCategoryName A [net.djvk.fireflyPlaidConnector2.transactions.PersonalFinanceCategoryEnum]
     *  name or one of its `Primary` names.
     */
    fun overrideFor(personalFinanceCategoryName: String): String? =
        normalizedOverrides[normalizeKey(personalFinanceCategoryName)]

    private fun normalizeKey(key: String) = key.trim().replace('-', '_').uppercase()
}
