package com.itau.account.support;

import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.Money;
import com.itau.account.domain.OwnerId;
import com.itau.account.domain.TransactionId;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static PostgreSQLContainer<?> postgres() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.withDatabaseName("account");
        container.withUsername("account");
        container.withPassword("account");
        return container;
    }

    public static BalanceEvent event(UUID account, UUID owner, UUID tx, long micros, String balance) {
        return new BalanceEvent(
                new TransactionId(tx),
                "CREDIT",
                Money.of(new BigDecimal("10.00"), "BRL"),
                "APPROVED",
                BalanceEvent.fromEpochMicros(micros),
                new AccountId(account),
                new OwnerId(owner),
                Instant.parse("2024-01-01T00:00:00Z"),
                "ENABLED",
                Money.of(new BigDecimal(balance), "BRL"),
                Instant.parse("2024-01-01T00:00:01Z")
        );
    }

    public static BalanceEvent event(long micros, String balance) {
        return event(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), micros, balance);
    }
}
