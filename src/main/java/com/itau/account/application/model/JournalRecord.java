package com.itau.account.application.model;

import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.domain.TransactionId;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


public record JournalRecord(
        UUID journalId,
        TransactionId transactionId,
        AccountId accountId,
        LocalDateTime sourceTimestamp,
        Instant receivedAt,
        ProcessingOutcome outcome,
        SnapshotEffect snapshotEffect,
        String correlationId,
        String attemptKey,
        Map<String, Object> decisionContext
) {
    public JournalRecord {
        Objects.requireNonNull(journalId, "journalId é obrigatório");
        Objects.requireNonNull(receivedAt, "receivedAt é obrigatório");
        Objects.requireNonNull(outcome, "outcome é obrigatório");
        Objects.requireNonNull(snapshotEffect, "snapshotEffect é obrigatório");
        Objects.requireNonNull(attemptKey, "attemptKey é obrigatório");
        Objects.requireNonNull(decisionContext, "decisionContext é obrigatório");
        decisionContext = Map.copyOf(decisionContext);
    }

    public static JournalRecord create(
            TransactionId transactionId,
            AccountId accountId,
            LocalDateTime sourceTimestamp,
            Instant receivedAt,
            ProcessingOutcome outcome,
            SnapshotEffect snapshotEffect,
            String correlationId,
            String attemptKey,
            Map<String, Object> decisionContext
    ) {
        return new JournalRecord(
                UUID.randomUUID(),
                transactionId,
                accountId,
                sourceTimestamp,
                receivedAt,
                outcome,
                snapshotEffect,
                correlationId,
                attemptKey,
                decisionContext
        );
    }
}
