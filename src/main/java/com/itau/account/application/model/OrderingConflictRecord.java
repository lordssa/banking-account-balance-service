package com.itau.account.application.model;

import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model for a persisted ordering conflict.
 */
public record OrderingConflictRecord(
        UUID conflictId,
        AccountId accountId,
        LocalDateTime sourceTimestamp,
        TransactionId transactionIdA,
        TransactionId transactionIdB,
        String recoveryState
) {
}
