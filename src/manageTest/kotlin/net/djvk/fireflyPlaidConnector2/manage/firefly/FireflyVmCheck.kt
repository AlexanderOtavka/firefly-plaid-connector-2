package net.djvk.fireflyPlaidConnector2.manage.firefly

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.ApiConfiguration
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AboutApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
import net.djvk.fireflyPlaidConnector2.manage.ManageException
import net.djvk.fireflyPlaidConnector2.sync.PlaidItem
import net.djvk.fireflyPlaidConnector2.sync.PlaidItemSource
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import kotlin.system.exitProcess

/**
 * Creates asset accounts in a real Firefly III through [PatFireflyDirectory], as the mapping
 * page does. Not a JUnit test: it needs a running Firefly, so `nix/vm-checks.nix` runs it in a
 * NixOS VM, with FIREFLY_URL and FIREFLY_TOKEN_FILE (a personal access token) set.
 */
fun main() {
    val url = System.getenv("FIREFLY_URL") ?: error("FIREFLY_URL is not set")
    val tokenFile = System.getenv("FIREFLY_TOKEN_FILE") ?: error("FIREFLY_TOKEN_FILE is not set")
    // Wired as Spring wires them in every mode but tests: CIO, and throw on a non-2xx status.
    val apiConfig = ApiConfiguration()
    val engine = apiConfig.getEngine()
    val clientConfig = apiConfig.getClientConfig()
    val accountsApi = AccountsApi(url, engine, clientConfig)
    val noItems = object : PlaidItemSource {
        override fun getAccountMapAndPlaidItems() = Pair(emptyMap<String, Int>(), emptySequence<PlaidItem>())
    }
    val syncHelper = SyncHelper(noItems, "", AboutApi(url, engine, clientConfig), TransactionsApi(url, engine, clientConfig), accountsApi, tokenFile)
    val directory = PatFireflyDirectory(syncHelper, accountsApi)

    val failures = mutableListOf<String>()
    fun check(ok: Boolean, what: String) {
        println("${if (ok) "ok" else "FAIL"}: $what")
        if (!ok) failures += what
    }

    runBlocking {
        val wanted = listOf(
            NewAssetAccount("Example Credit Card", AccountRoleProperty.ccAsset, "Plaid account ...0001"),
            NewAssetAccount("Example Savings", AccountRoleProperty.savingAsset, "Plaid account ...0002"),
            NewAssetAccount("Example Checking", AccountRoleProperty.defaultAsset, null),
        )
        for (account in wanted) {
            val created = runCatching { directory.createAssetAccount(account) }
            check(created.isSuccess, "create ${account.role.value} \"${account.name}\": ${created.exceptionOrNull()?.message ?: "created"}")
            created.getOrNull()?.let {
                check(it.name == account.name && it.role == account.role.value, "created account is $it")
            }
        }

        val listed = directory.assetAccounts()
        for (account in wanted) {
            check(listed.any { it.name == account.name && it.role == account.role.value }, "\"${account.name}\" is listed")
        }

        // A taken name still fails, now with Firefly's reason rather than a guess.
        val duplicate = runCatching { directory.createAssetAccount(wanted.first()) }.exceptionOrNull()
        check(
            duplicate is ManageException && duplicate.status == 422 && duplicate.message!!.contains("already in use"),
            "a duplicate name is refused with Firefly's reason: ${duplicate?.message}",
        )
    }

    if (failures.isNotEmpty()) {
        System.err.println("${failures.size} check(s) failed")
        exitProcess(1)
    }
    exitProcess(0)
}
