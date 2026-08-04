package com.itau.account.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable ingested financial event. Ordering uses {@link #sourceTimestamp()} at microsecond precision.
 */
public record BalanceEvent(
        TransactionId transactionId,
        String transactionType,
        Money transactionAmount,
        String transactionStatus,
        LocalDateTime sourceTimestamp,
        AccountId accountId,
        OwnerId ownerId,
        Instant accountCreatedAt,
        String accountStatus,
        Money authoritativeBalance,
        Instant receivedAt
) {
    public BalanceEvent {
        Objects.requireNonNull(transactionId, "transactionId é obrigatório");
        Objects.requireNonNull(transactionType, "transactionType é obrigatório");
        Objects.requireNonNull(transactionAmount, "transactionAmount é obrigatório");
        Objects.requireNonNull(transactionStatus, "transactionStatus é obrigatório");
        Objects.requireNonNull(sourceTimestamp, "sourceTimestamp é obrigatório");
        Objects.requireNonNull(accountId, "accountId é obrigatório");
        Objects.requireNonNull(ownerId, "ownerId é obrigatório");
        Objects.requireNonNull(accountStatus, "accountStatus é obrigatório");
        Objects.requireNonNull(authoritativeBalance, "authoritativeBalance é obrigatório");
        Objects.requireNonNull(receivedAt, "receivedAt é obrigatório");
        if (transactionType.isBlank()) {
            throw new IllegalArgumentException("transactionType é obrigatório");
        }
        if (transactionStatus.isBlank()) {
            throw new IllegalArgumentException("transactionStatus é obrigatório");
        }
        if (accountStatus.isBlank()) {
            throw new IllegalArgumentException("accountStatus é obrigatório");
        }
    }

    public static LocalDateTime fromEpochMicros(long epochMicros) {
        long seconds = Math.floorDiv(epochMicros, 1_000_000L);
        long micros = Math.floorMod(epochMicros, 1_000_000L);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds, micros * 1_000L), java.time.ZoneOffset.UTC);
    }
}
