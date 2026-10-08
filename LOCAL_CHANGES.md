# Local changes

This fork is derived from
[`dvankley/firefly-plaid-connector-2`](https://github.com/dvankley/firefly-plaid-connector-2)
v1.5.1 and contains local modifications. See `UPSTREAM.md` for how it tracks upstream.

## 2026-08-17

- Added file-backed credentials for Plaid, Firefly III, and Plaid Items.
- Replaced access tokens in cursor storage and logs with one-way Item identifiers.
- Made cursor writes atomic and owner-only on POSIX filesystems.
- Added bounded exponential backoff for transient Plaid failures.
- Kept polled mode running across temporary initialization and sync failures.
- Delayed cursor advancement until Firefly III updates and cursor persistence succeed.
- Propagated failed Firefly III mutations so polling retries them before advancing.
- Updated pending-to-posted transactions in place while preserving Firefly III metadata.
- Kept pending transactions out of transfer matching and disabled Firefly III
  rule reapplication during metadata-preserving settlement updates.
- Added regression coverage for secret handling, retries, cursor integrity, and a
  synthetic credit card pending-to-posted transaction.

## 2026-09-27: management dashboard and database item store

A third sync mode, `manage`, serves a dashboard for linking, relinking, repairing, and
backfilling Plaid Items; a fourth, `import`, copies the configured `accounts:` block into
its database once.

To keep rebases on upstream cheap, nearly all of this lives in its own source tree,
`src/manage/` (tests in `src/manageTest/`), added to the main and test source sets by
`build.gradle.kts`. Changes to upstream files are limited to:

- `PlaidItemSource` (`sync/PlaidItemSource.kt`): `SyncHelper.getAccountMapAndPlaidItems`
  delegates to it. `ConfigPlaidItemSource` is the upstream logic and stays the default; the
  database implementation is in the manage tree. `AccountConfigs.accounts` defaults to empty.
- `CursorStore` (`sync/CursorStore.kt`): `CursorManager` (the cursor file) implements it and
  stays the default; database mode keeps each cursor on its Item's row.
- `SyncOutcomeRecorder` (`sync/SyncOutcomeRecorder.kt`): a no-op by default. Polled mode
  reports per-Item success and failure, with Plaid's `error_code` parsed from the
  `ClientRequestException`, including on the `allowItemToFail` path; batch mode reports its
  start, counts, and result.
- `SyncHelper.optimisticInsertBatchIntoFirefly` returns inserted/duplicate/failed counts.
- `PolledSyncOrchestrator` re-reads Items and cursors every cycle, and initializes cursors
  for Items that appeared since the last one, instead of reading Items once at start.
- `fireflyPlaidConnector2.itemStore: config|database` (default `config`) selects between
  the two sets of implementations.
- `application.yml` turns off the web server and DataSource/Flyway/JDBC auto-configuration,
  which the dashboard's dependencies would otherwise switch on in every mode. The manage
  tree's `ConnectorModeEnvironmentPostProcessor` applies the same when
  `SPRING_CONFIG_LOCATION` replaces that file, and enables the servlet stack for `manage`
  only. Batch and polled modes still run with no database configured.
- `SyncMode` lists the two new modes.
- `.gitignore` ignores `*.sql` upstream; `!src/manage/resources/db/**/*.sql` un-ignores the
  dashboard's Flyway migrations, which a test also checks are on the classpath.
- New dependencies: Spring Web, JDBC, Thymeleaf, Security and OAuth2 client, Flyway,
  Micrometer Prometheus, fabric8 kubernetes-client, and the PostgreSQL driver; for tests,
  spring-security-test and zonky embedded-postgres. zonky's binaries do not run on NixOS;
  there, set `PLAID_MANAGER_TEST_PG_PORT` to a local PostgreSQL that trusts the `postgres`
  user.

## 2026-10-04: backfills match earlier imports

Upstream's batch mode inserts everything and relies on Firefly's duplicate hash, which misses
a re-import once descriptions or categories change, and every transaction of a relinked Item
(new Plaid `transaction_id`s, so new `external_id`s). Batch mode now matches first.

- `transactions/BackfillReconciler.kt` (new): pairs converted transactions with existing
  single-split `plaid-` imports, by `external_id`, else by account, direction, and amount
  within `batch.reconcile.dateWindowDays` (default 4), one to one. Matches become in-place
  updates (only when something changed); unclear pairings, and imports nothing matched beside
  one that did (probable duplicates), become `ReviewCandidate`s. Nothing is written for them.
- `sync/ImportedTransactionFetcher.kt` (new): pages through each mapped account's
  transactions over the run's range plus the window.
- `BatchSyncRunner`: plans with the two above, applies updates, then inserts; skips both,
  and the initial balance, when `batch.dryRun` is set. `batch.reconcile.enable: false`
  restores upstream behaviour. Reviews are also logged.
- `TransactionConverter`: `refreshImported` (like `preserveUserMetadata`, but refreshes a
  description, payee, or category only while it is still connector text, from
  `connectorText`, and keeps a reconciled transaction's date) and `backfillReconciler`.
- `InsertCounts` gains `matched`, `updated`, and `needsReview`; `BatchOutcome` gains
  `dryRun` and `reviews`.

In the manage tree: migration `V2__backfill_review.sql`, the review page and its API, and a
dry-run option on the backfill form.

## 2026-10-05: Plaid requests omit null fields

`PlaidApiWrapper`'s mapper uses `JsonInclude.Include.NON_NULL`. The generated models sent
every unset field as `null`, which Plaid ignored on the upstream calls but not on
`/link/token/create`, where `"access_tokens": null` fails with `INVALID_FIELD`, so the
dashboard could not link a bank. Upstream's request-shape assertions in
`PlaidApiWrapperTest` drop their `null` fields accordingly.

## 2026-10-05: the dashboard looks like Firefly III, with dark mode

Presentation only, all in the manage tree: no route, API, script behaviour, CSP, or header
changes.

- `static/assets/plaid-manager.css` is rewritten to follow Firefly III's AdminLTE 2 look (blue
  header with a darker logo block, gray content, boxes with a colored top border, bootstrap-style
  buttons, labels, striped tables) in plain CSS, since the CSP allows styles from `'self'`
  only. Colors are custom properties with a `prefers-color-scheme: dark` set, and
  `color-scheme: light dark` (also a `<meta>` in the head fragment) styles form controls.
- The old `.status-active`/`.status-error` text colors matched the `status-*` class on each
  Item card and turned everything inside it green or red. Statuses are now `label-<status>`
  badges; cards carry `item-<status>`, which only colors the top border.
- Templates use `box`/`btn`/`label`/`alert`/`callout` markup. Tables sit in a horizontally
  scrolling wrapper; the backfill form is a grid that stacks on phones; Retire and "Delete it"
  are red buttons; the `#message` error is an alert box. The login-failed, logged-out, and
  error pages get a header without the logout form (`fragments :: header-plain`).

## 2026-10-05: dashboard header, local times, and live backfill logs

- The header fragments are `page-header` and `page-header-plain`. A fragment selector named
  `header` also matches every `<header>` element in `fragments.html`, so each page got both
  headers.
- Timestamps carry their instant in `<time datetime>`, and `plaid-manager.js` rewrites them in
  the browser's time zone. The server-rendered text, in the pod's zone (UTC), is the fallback.
- `streamLog` flushes after every chunk. It used `copyTo`, which never flushes, so a running
  backfill's log stayed in the servlet's response buffer until the pod exited.

## 2026-10-05: configurable request timeout

- `fireflyPlaidConnector2.http.requestTimeoutMillis` (default 600000) replaces the fixed 60 s
  `HttpTimeout` in `ApiConfiguration`, which both the Plaid and Firefly III clients use. Firefly
  III recalculates the running balance of every later transaction in the account on each
  non-batch store, one commit per row, so a back-dated backfill insert could take over a minute on
  a server with slow disks, and the first timeout ended the Job.

## 2026-10-07: mapping creates Firefly asset accounts

- On a new link (no account of the Item mapped yet), each account the predecessor's mapping
  does not cover defaults to **Create a new asset account**, enabled, named after the Plaid
  account (mask appended when that name is taken), or to an active Firefly account of the
  same name that no enabled account uses. `ItemService.propose` decides; the name is editable.
- Saving creates them through `POST /api/v1/accounts` (`FireflyDirectory.createAssetAccount`,
  with the connector's personal access token) before the mapping transaction: role
  `ccAsset` (monthly full, payment date the 1st) for credit, `savingAsset` for savings-like
  depository subtypes, else `defaultAsset`; default currency; no opening balance, which a
  backfill sets. Names already in Firefly are refused before anything is created; if a later
  step fails, the error names what was created, and a reload preselects it by name.
- `MappingEntry`/`MappingChange` gain `newFireflyAccountName`; `saveMapping` is `suspend`.
