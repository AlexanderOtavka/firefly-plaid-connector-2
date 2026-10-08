package net.djvk.fireflyPlaidConnector2.constants

enum class SyncMode {
    batch,
    polled,

    // Local changes, implemented under src/manage/:
    // the management dashboard,
    manage,

    // and a one-shot copy of the configured `accounts:` into the database item store.
    `import`,
}
