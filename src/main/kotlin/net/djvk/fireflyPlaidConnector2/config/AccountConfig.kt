package net.djvk.fireflyPlaidConnector2.config

data class AccountConfig(
    val fireflyAccountId: Int,
    val plaidItemAccessToken: String? = null,
    val plaidAccountId: String,
    val plaidItemAccessTokenFile: String? = null,
)