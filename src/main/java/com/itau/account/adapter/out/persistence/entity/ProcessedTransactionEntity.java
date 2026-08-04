package com.itau.account.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "processed_transaction")
public class ProcessedTransactionEntity {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "source_timestamp", nullable = false)
    private LocalDateTime sourceTimestamp;

    @Column(name = "first_outcome", nullable = false, length = 32)
    private String firstOutcome;

    protected ProcessedTransactionEntity() {
    }

    public UUID getTransactionId() { return transactionId; }
    public UUID getAccountId() { return accountId; }
    public LocalDateTime getSourceTimestamp() { return sourceTimestamp; }
    public String getFirstOutcome() { return firstOutcome; }
}
