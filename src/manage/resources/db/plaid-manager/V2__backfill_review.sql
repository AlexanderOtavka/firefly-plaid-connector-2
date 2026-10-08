-- #213: backfills match transactions the connector already imported and update them in
-- place. A run records how many it matched and updated, and lists what it could not decide
-- safely for the owner to resolve.

ALTER TABLE backfill_run
    -- A dry run writes nothing to Firefly; inserted and updated are what it would have done.
    ADD COLUMN dry_run      BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN matched      INTEGER,
    ADD COLUMN updated      INTEGER,
    ADD COLUMN needs_review INTEGER;

CREATE TABLE backfill_review (
    id                     BIGSERIAL PRIMARY KEY,
    run_id                 BIGINT      NOT NULL REFERENCES backfill_run (id),
    -- unmatched: a transaction the run did not insert because several imports could be it.
    -- leftover: an import nothing matched, beside one that did; probably a duplicate.
    kind                   TEXT        NOT NULL CHECK (kind IN ('unmatched', 'leftover')),
    reason                 TEXT        NOT NULL,
    tx_date                DATE        NOT NULL,
    amount                 TEXT        NOT NULL,
    description            TEXT        NOT NULL,
    -- unmatched only: the Firefly TransactionSplit the run would have inserted, as the
    -- Firefly API's JSON.
    proposed               JSONB,
    -- Firefly transaction ids: for unmatched, the imports it could be; for leftover, the
    -- possible duplicate. With their external_id when the review was made, so an action
    -- is refused once one has changed.
    candidate_ids          TEXT[]      NOT NULL,
    candidate_external_ids TEXT[]      NOT NULL,
    -- leftover only: the imports the run matched that it may duplicate.
    related_ids            TEXT[]      NOT NULL DEFAULT '{}',
    -- Normalized text the connector could have written for proposed; see
    -- TransactionConverter.refreshImported.
    generated              TEXT[]      NOT NULL DEFAULT '{}',
    status                 TEXT        NOT NULL DEFAULT 'open' CHECK (
        status IN ('open', 'imported', 'merged', 'deleted', 'dismissed')
    ),
    -- The Firefly transaction it was merged into.
    merged_into            TEXT,
    resolved_at            TIMESTAMPTZ,
    CHECK ((status = 'merged') = (merged_into IS NOT NULL)),
    CHECK ((kind = 'unmatched') = (proposed IS NOT NULL)),
    CHECK (cardinality(candidate_ids) = cardinality(candidate_external_ids))
);

CREATE INDEX backfill_review_run_id ON backfill_review (run_id);
