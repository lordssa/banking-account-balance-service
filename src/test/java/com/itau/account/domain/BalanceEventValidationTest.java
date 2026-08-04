package com.itau.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceEventValidationTest {

    @Test
    void rejectsBlankTransactionType() {
        assertThatThrownBy(() -> new BalanceEvent(
                TransactionId.parse("550e8400-e29b-41d4-a716-446655440000"),
                " ",
                Money.of(new BigDecimal("1.00"), "BRL"),
                "APPROVED",
                BalanceEvent.fromEpochMicros(1L),
                AccountId.parse("550e8400-e29b-41d4-a716-446655440001"),
                OwnerId.parse("550e8400-e29b-41d4-a716-446655440002"),
                Instant.now(),
                "ENABLED",
                Money.of(new BigDecimal("1.00"), "BRL"),
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
