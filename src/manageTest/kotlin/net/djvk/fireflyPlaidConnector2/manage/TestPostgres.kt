package net.djvk.fireflyPlaidConnector2.manage

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.DriverManager
import java.util.UUID

/**
 * A throwaway PostgreSQL for tests, with no Docker.
 *
 * By default this starts zonky's embedded PostgreSQL. Its binaries are generic Linux builds,
 * which do not run on NixOS; there, start any local PostgreSQL with trust auth for the
 * `postgres` user and point the tests at it:
 *
 *     PLAID_MANAGER_TEST_PG_PORT=55432 ./gradlew test
 */
object TestPostgres {
    data class Database(val url: String, val username: String, val password: String)

    private val port: Int by lazy {
        System.getenv("PLAID_MANAGER_TEST_PG_PORT")?.toInt() ?: embedded.port
    }

    private val embedded: EmbeddedPostgres by lazy {
        EmbeddedPostgres.start().also { pg -> Runtime.getRuntime().addShutdownHook(Thread { pg.close() }) }
    }

    /** A new, empty database. */
    fun freshDatabase(): Database {
        val name = "plaid_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection("jdbc:postgresql://localhost:$port/postgres", "postgres", "postgres").use {
            it.createStatement().use { statement -> statement.execute("CREATE DATABASE $name") }
        }
        return Database("jdbc:postgresql://localhost:$port/$name", "postgres", "unused")
    }
}
