package com.itau.account.application.model;

/**
 * Result of attempting to insert a processed_transaction row.
 */
public enum ClaimResult {
    /** Row inserted successfully. */
    INSERTED,
    /** Primary key conflict — this transaction_id was already processed. */
    DUPLICATE_TRANSACTION,
    /**
     * Unique conflict on (account_id, source_timestamp) for a non-conflicting outcome —
     * another distinct transaction already owns this ordering key.
     */
    ACCOUNT_TIMESTAMP_TAKEN
}
