package net.djvk.fireflyPlaidConnector2.sync

import java.security.MessageDigest

typealias PlaidItemKey = String

/**
 * A configured Plaid Item and the accounts selected from it.
 *
 * The key is a one-way identifier suitable for logs and cursor persistence;
 * the access token must never be persisted or logged.
 */
data class PlaidItem(
    val key: PlaidItemKey,
    val accessToken: PlaidAccessToken,
    val accountIds: List<PlaidAccountId>,
) {
    companion object {
        fun keyForAccessToken(accessToken: PlaidAccessToken): PlaidItemKey {
            val digest = MessageDigest
                .getInstance("SHA-256")
                .digest(accessToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "item-$digest"
        }

        fun from(
            accessToken: PlaidAccessToken,
            accountIds: List<PlaidAccountId>,
        ): PlaidItem = PlaidItem(
            key = keyForAccessToken(accessToken),
            accessToken = accessToken,
            accountIds = accountIds,
        )
    }
}
