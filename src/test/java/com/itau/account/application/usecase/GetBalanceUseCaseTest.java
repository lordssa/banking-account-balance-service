package com.itau.account.application.usecase;

import com.itau.account.application.exception.AccountNotFoundException;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.domain.AccountBalanceSnapshot;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.Money;
import com.itau.account.domain.OwnerId;
import com.itau.account.domain.TransactionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetBalanceUseCaseTest {

    @Mock AccountBalanceSnapshotPort snapshotPort;
    @InjectMocks GetBalanceUseCase useCase;

    @Test
    void returnsBalanceView() {
        AccountId accountId = new AccountId(UUID.randomUUID());
        var snapshot = new AccountBalanceSnapshot(
                accountId,
                new OwnerId(UUID.randomUUID()),
                Money.of(new BigDecimal("12.34"), "BRL"),
                BalanceEvent.fromEpochMicros(1_000L),
                "ENABLED",
                new TransactionId(UUID.randomUUID())
        );
        when(snapshotPort.findByAccountId(accountId)).thenReturn(Optional.of(snapshot));

        var view = useCase.getBalance(accountId);
        assertThat(view.balance().amountPlainString()).isEqualTo("12.34");
        assertThat(view.accountId()).isEqualTo(accountId);
    }

    @Test
    void missingAccountThrows() {
        AccountId accountId = new AccountId(UUID.randomUUID());
        when(snapshotPort.findByAccountId(accountId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.getBalance(accountId))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
