package com.itau.account.application.model;

import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable write model for an equal-timestamp ordering conflict (explicit SQL insert).
 */
public record OrderingConflictInsert(
        UUID conflictId,
        AccountId accountId,
        LocalDateTime sourceTimestamp,
        TransactionId transactionIdA,
        TransactionId transactionIdB
) {
    public OrderingConflictInsert {
        Objects.requireNonNull(conflictId, "conflictId é obrigatório");
        Objects.requireNonNull(accountId, "accountId é obrigatório");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp é obrigatório");
        Objects.requireNonNull(transactionIdA, "transactionIdA é obrigatório");
        Objects.requireNonNull(transactionIdB, "transactionIdB é obrigatório");
    }

    public static OrderingConflictInsert between(
            AccountId accountId,
            LocalDateTime sourceTimestamp,
            TransactionId transactionIdA,
            TransactionId transactionIdB
    ) {
        return new OrderingConflictInsert(
                UUID.randomUUID(),
                accountId,
                sourceTimestamp,
                transactionIdA,
                transactionIdB
        );
    }
}
