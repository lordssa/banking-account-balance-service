package com.itau.account.application.model;

import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.TransactionId;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable write model for claiming a transaction id (simple insert).
 */
public record ProcessedTransactionInsert(
        TransactionId transactionId,
        AccountId accountId,
        LocalDateTime sourceTimestamp,
        ProcessingOutcome outcome
) {
    public ProcessedTransactionInsert {
        Objects.requireNonNull(transactionId, "transactionId é obrigatório");
        Objects.requireNonNull(accountId, "accountId é obrigatório");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp é obrigatório");
        Objects.requireNonNull(outcome, "outcome é obrigatório");
    }
}
