package com.itau.account.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_balance_snapshot")
public class AccountBalanceSnapshotEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "balance_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal balanceAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "source_timestamp", nullable = false)
    private LocalDateTime sourceTimestamp;

    @Column(name = "account_status", nullable = false, length = 32)
    private String accountStatus;

    @Column(name = "account_created_at")
    private LocalDateTime accountCreatedAt;

    @Column(name = "winning_transaction_id", nullable = false)
    private UUID winningTransactionId;

    public UUID getAccountId() { return accountId; }
    public UUID getOwnerId() { return ownerId; }
    public BigDecimal getBalanceAmount() { return balanceAmount; }
    public String getCurrency() { return currency; }
    public LocalDateTime getSourceTimestamp() { return sourceTimestamp; }
    public String getAccountStatus() { return accountStatus; }
    public UUID getWinningTransactionId() { return winningTransactionId; }
}
