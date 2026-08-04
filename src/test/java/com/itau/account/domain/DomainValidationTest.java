package com.itau.account.domain;

import com.itau.account.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainValidationTest {

    @Test
    void moneyKeepsExactDecimal() {
        Money money = Money.parse("10.50", "BRL");
        assertThat(money.amountPlainString()).isEqualTo("10.5");
        assertThat(money.currency().value()).isEqualTo("BRL");
    }

    @Test
    void rejectsInvalidCurrency() {
        assertThatThrownBy(() -> CurrencyCode.of("REAL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesAccountUuid() {
        AccountId id = AccountId.parse("550e8400-e29b-41d4-a716-446655440000");
        assertThat(id.value().toString()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void rejectsMalformedAccountUuid() {
        assertThatThrownBy(() -> AccountId.parse("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void microsecondTimestampConversion() {
        var ts = BalanceEvent.fromEpochMicros(1_700_000_000_123_456L);
        assertThat(ts.getNano()).isEqualTo(123_456_000);
    }

    @Test
    void orderingComparesStrictlyNewer() {
        var older = BalanceEvent.fromEpochMicros(1000);
        var newer = BalanceEvent.fromEpochMicros(1001);
        assertThat(EventOrdering.isStrictlyNewer(newer, older)).isTrue();
        assertThat(EventOrdering.isEqualTimestamp(older, older)).isTrue();
    }

    @Test
    void moneyRejectsNullAmount() {
        assertThatThrownBy(() -> new Money(null, CurrencyCode.of("BRL")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankOwnerAndTransactionIds() {
        assertThatThrownBy(() -> OwnerId.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionId.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse(" ", "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("abc", "BRL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountId.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OwnerId.parse("not-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionId.parse("not-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyNormalizesTrailingZerosAndRejectsNegativeScale() {
        assertThat(Money.of(new BigDecimal("100.00"), "BRL").amountPlainString()).isEqualTo("100");
        assertThatThrownBy(() -> new Money(BigDecimal.valueOf(10, -1), CurrencyCode.of("BRL")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balanceEventRejectsBlankStatuses() {
        var base = TestFixtures.event(1L, "1.00");
        assertThatThrownBy(() -> new BalanceEvent(
                base.transactionId(), " ", base.transactionAmount(), base.transactionStatus(),
                base.sourceTimestamp(), base.accountId(), base.ownerId(), base.accountCreatedAt(),
                base.accountStatus(), base.authoritativeBalance(), base.receivedAt()
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
