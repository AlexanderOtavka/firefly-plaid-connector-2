-- The Plaid connector's item store: linked Items, their accounts and Firefly mappings, and
-- backfill runs. Access tokens live only in plaid_item.access_token; see ItemRepository.

CREATE TABLE plaid_item (
    id                   BIGSERIAL PRIMARY KEY,
    plaid_item_id        TEXT        NOT NULL UNIQUE,
    -- NULL once the Item is retired and removed at Plaid.
    access_token         TEXT,
    institution_id       TEXT,
    institution_name     TEXT,
    -- transactions.days_requested the Item was linked with. Fixed for the Item's lifetime.
    days_requested       INTEGER     NOT NULL CHECK (days_requested > 0),
    linked_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    status               TEXT        NOT NULL CHECK (
        status IN ('pending_history', 'active', 'login_required', 'error', 'retired')
    ),
    -- Set on an Item when a replacement is linked for it.
    replaced_by          BIGINT REFERENCES plaid_item (id),
    retired_at           TIMESTAMPTZ,
    -- /transactions/sync cursor, set by the manager once Plaid's historical pull is complete.
    sync_cursor          TEXT,
    -- When the Item first got a cursor, i.e. became pollable. A never-synced Item ages
    -- from here for the stale-sync alert.
    polling_since        TIMESTAMPTZ,
    last_sync_at         TIMESTAMPTZ,
    last_sync_added      INTEGER,
    last_error_code      TEXT,
    last_error_message   TEXT,
    last_error_at        TIMESTAMPTZ,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    CHECK (status = 'retired' OR access_token IS NOT NULL)
);

CREATE TABLE plaid_account (
    id                    BIGSERIAL PRIMARY KEY,
    item_id               BIGINT  NOT NULL REFERENCES plaid_item (id),
    plaid_account_id      TEXT    NOT NULL UNIQUE,
    persistent_account_id TEXT,
    name                  TEXT    NOT NULL,
    mask                  TEXT,
    type                  TEXT    NOT NULL,
    subtype               TEXT,
    firefly_account_id    INTEGER,
    enabled               BOOLEAN NOT NULL DEFAULT false,
    CHECK (NOT enabled OR firefly_account_id IS NOT NULL)
);

CREATE INDEX plaid_account_item_id ON plaid_account (item_id);

-- One enabled Plaid account per Firefly account, so an Item and its replacement can never
-- both import into the same Firefly account.
CREATE UNIQUE INDEX plaid_account_one_enabled_per_firefly_account
    ON plaid_account (firefly_account_id)
    WHERE enabled;

CREATE TABLE backfill_run (
    id               BIGSERIAL PRIMARY KEY,
    item_id          BIGINT      NOT NULL REFERENCES plaid_item (id),
    -- plaid_account ids included in the run.
    account_ids      BIGINT[]    NOT NULL,
    days             INTEGER     NOT NULL CHECK (days > 0),
    deadline_seconds INTEGER     NOT NULL CHECK (deadline_seconds > 0),
    -- Deterministic (firefly-plaid-backfill-run-<id>) and written with the row, so a crash
    -- between inserting the row and creating the Job cannot leave an orphan the
    -- reconciler cannot find.
    job_name         TEXT        NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    status           TEXT        NOT NULL CHECK (
        status IN ('pending', 'running', 'succeeded', 'failed', 'deadline_exceeded')
    ),
    fetched          INTEGER,
    inserted         INTEGER,
    duplicates       INTEGER,
    failed           INTEGER,
    oldest_date      DATE,
    error            TEXT
);

CREATE INDEX backfill_run_item_id ON backfill_run (item_id);

-- Only one backfill in flight across the whole system.
CREATE UNIQUE INDEX backfill_run_one_active
    ON backfill_run ((true))
    WHERE status IN ('pending', 'running');
