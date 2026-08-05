-- Lookup index for findOtherTransactionAt hot path.
-- Partial unique index uq_processed_account_source_ts cannot serve queries that omit its WHERE predicate.
CREATE INDEX IF NOT EXISTS idx_processed_account_ts_lookup
    ON processed_transaction (
        account_id,
        source_timestamp,
        first_processed_at
    )
    INCLUDE (transaction_id, first_outcome);
