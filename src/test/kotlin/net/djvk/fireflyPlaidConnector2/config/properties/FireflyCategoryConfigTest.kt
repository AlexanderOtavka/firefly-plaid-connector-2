package net.djvk.fireflyPlaidConnector2.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

internal class FireflyCategoryConfigTest {
    /**
     * The connector's config file spells its properties in camel case, which only reaches a
     * kebab-case `@ConfigurationProperties` prefix through Spring's relaxed binding. Bind the
     * properties exactly as they are written in the config file so a prefix that silently fails
     * to match is caught here rather than by a batch run that quietly categorizes nothing.
     */
    @Test
    fun `binds the properties as they are spelled in the config file`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "fireflyPlaidConnector2.categorization.firefly.enable" to "true",
                "fireflyPlaidConnector2.categorization.firefly.overrides.food-and-drink-groceries"
                    to "Groceries",
            )
        )

        val config = Binder(source)
            .bind("firefly-plaid-connector2.categorization.firefly", FireflyCategoryConfig::class.java)
            .get()

        assertThat(config.enable).isTrue()
        assertThat(config.overrideFor("FOOD_AND_DRINK_GROCERIES")).isEqualTo("Groceries")
    }

    @Test
    fun `defaults to disabled with no overrides`() {
        val config = Binder(MapConfigurationPropertySource(emptyMap<String, Any>()))
            .bindOrCreate("firefly-plaid-connector2.categorization.firefly", FireflyCategoryConfig::class.java)

        assertThat(config.enable).isFalse()
        assertThat(config.overrideFor("FOOD_AND_DRINK_GROCERIES")).isNull()
    }

    @Test
    fun `override keys match regardless of case and separator style`() {
        val config = FireflyCategoryConfig(
            enable = true,
            overrides = mapOf("FOOD_AND_DRINK" to "Eating"),
        )

        assertThat(config.overrideFor("food-and-drink")).isEqualTo("Eating")
        assertThat(config.overrideFor("FOOD_AND_DRINK")).isEqualTo("Eating")
        assertThat(config.overrideFor("TRAVEL")).isNull()
    }
}
