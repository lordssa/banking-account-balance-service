package com.itau.account.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ordering_conflict")
public class OrderingConflictEntity {

    @Id
    @Column(name = "conflict_id", nullable = false)
    private UUID conflictId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "source_timestamp", nullable = false)
    private LocalDateTime sourceTimestamp;

    @Column(name = "transaction_id_a", nullable = false)
    private UUID transactionIdA;

    @Column(name = "transaction_id_b", nullable = false)
    private UUID transactionIdB;

    @Column(name = "recovery_state", nullable = false, length = 32)
    private String recoveryState;

    protected OrderingConflictEntity() {
    }

    public UUID getConflictId() { return conflictId; }
    public UUID getAccountId() { return accountId; }
    public LocalDateTime getSourceTimestamp() { return sourceTimestamp; }
    public UUID getTransactionIdA() { return transactionIdA; }
    public UUID getTransactionIdB() { return transactionIdB; }
    public String getRecoveryState() { return recoveryState; }
}
