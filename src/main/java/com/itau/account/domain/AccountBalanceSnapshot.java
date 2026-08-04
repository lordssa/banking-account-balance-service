package com.itau.account.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public record AccountBalanceSnapshot(
        AccountId accountId,
        OwnerId ownerId,
        Money balance,
        LocalDateTime sourceTimestamp,
        String accountStatus,
        TransactionId winningTransactionId
) {
    public AccountBalanceSnapshot {
        Objects.requireNonNull(accountId, "accountId é obrigatório");
        Objects.requireNonNull(ownerId, "ownerId é obrigatório");
        Objects.requireNonNull(balance, "saldo é obrigatório");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp é obrigatório");
        Objects.requireNonNull(accountStatus, "accountStatus é obrigatório");
        Objects.requireNonNull(winningTransactionId, "winningTransactionId é obrigatório");
    }
}
