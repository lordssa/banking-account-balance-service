package com.itau.account.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "journal_processing_record")
public class JournalProcessingRecordEntity {

    @Id
    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "source_timestamp")
    private LocalDateTime sourceTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "snapshot_effect", nullable = false, length = 32)
    private String snapshotEffect;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "attempt_key", nullable = false, length = 128)
    private String attemptKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_context", nullable = false, columnDefinition = "jsonb")
    private String decisionContext;

    protected JournalProcessingRecordEntity() {
    }

    public static JournalProcessingRecordEntity create(
            UUID journalId,
            UUID transactionId,
            UUID accountId,
            LocalDateTime sourceTimestamp,
            Instant receivedAt,
            String outcome,
            String snapshotEffect,
            String correlationId,
            String attemptKey,
            String decisionContextJson
    ) {
        var entity = new JournalProcessingRecordEntity();
        entity.journalId = journalId;
        entity.transactionId = transactionId;
        entity.accountId = accountId;
        entity.sourceTimestamp = sourceTimestamp;
        entity.receivedAt = receivedAt;
        entity.outcome = outcome;
        entity.snapshotEffect = snapshotEffect;
        entity.correlationId = correlationId;
        entity.attemptKey = attemptKey;
        entity.decisionContext = decisionContextJson;
        return entity;
    }

    public UUID getJournalId() { return journalId; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getAccountId() { return accountId; }
    public LocalDateTime getSourceTimestamp() { return sourceTimestamp; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getOutcome() { return outcome; }
    public String getSnapshotEffect() { return snapshotEffect; }
    public String getCorrelationId() { return correlationId; }
    public String getAttemptKey() { return attemptKey; }
    public String getDecisionContext() { return decisionContext; }
}
