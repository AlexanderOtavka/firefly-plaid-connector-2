-- The range of transaction dates synced, so the owner can see where Plaid's history starts
-- and import anything older by hand.

-- A backfill already recorded its earliest transaction date; it records its latest too.
ALTER TABLE backfill_run
    ADD COLUMN newest_date DATE;

-- Widened by every poll that adds transactions and every backfill that writes them.
ALTER TABLE plaid_item
    ADD COLUMN earliest_tx_date DATE,
    ADD COLUMN latest_tx_date   DATE;

-- Earlier backfills only know their earliest date. The latest fills in at the next poll that
-- adds a transaction.
UPDATE plaid_item
   SET earliest_tx_date = (
       SELECT min(oldest_date) FROM backfill_run
        WHERE item_id = plaid_item.id AND NOT dry_run AND status = 'succeeded'
   );
