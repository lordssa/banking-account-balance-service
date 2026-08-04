package com.itau.account.application.usecase;

import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.OrderingConflictInsert;
import com.itau.account.application.model.ProcessedTransactionInsert;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.domain.AccountBalanceSnapshot;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.Money;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.domain.TransactionId;
import com.itau.account.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestEqualTimestampConflictTest {

    @Mock AccountBalanceSnapshotPort snapshotPort;
    @Mock ProcessedTransactionPort processedTransactionPort;
    @Mock JournalPort journalPort;
    @Mock OrderingConflictPort orderingConflictPort;

    IngestBalanceEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new IngestBalanceEventUseCase(snapshotPort, processedTransactionPort, journalPort, orderingConflictPort);
    }

    @Test
    void otherProcessedTransactionAtSameTimestampIsConflict() {
        var event = TestFixtures.event(1_000L, "100.00");
        var other = new TransactionId(UUID.randomUUID());
        when(processedTransactionPort.findOutcome(event.transactionId())).thenReturn(Optional.empty());
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.of(other));
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);

        var result = useCase.ingest(event, "attempt-eq", "corr-eq");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
        assertThat(result.snapshotEffect()).isEqualTo(SnapshotEffect.UNCHANGED);
        ArgumentCaptor<OrderingConflictInsert> captor = ArgumentCaptor.forClass(OrderingConflictInsert.class);
        verify(orderingConflictPort).recordConflict(captor.capture());
        assertThat(captor.getValue().transactionIdA()).isEqualTo(other);
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void snapshotEqualTimestampDifferentWinnerIsConflict() {
        var event = TestFixtures.event(1_000L, "100.00");
        var winner = new TransactionId(UUID.randomUUID());
        var snapshot = new AccountBalanceSnapshot(
                event.accountId(),
                event.ownerId(),
                Money.of(new BigDecimal("80.00"), "BRL"),
                BalanceEvent.fromEpochMicros(1_000L),
                "ENABLED",
                winner
        );
        when(processedTransactionPort.findOutcome(event.transactionId())).thenReturn(Optional.empty());
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.empty());
        when(snapshotPort.findByAccountId(event.accountId())).thenReturn(Optional.of(snapshot));
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);

        var result = useCase.ingest(event, "attempt-snap", "corr-snap");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
        verify(orderingConflictPort).recordConflict(any(OrderingConflictInsert.class));
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void uniqueViolationOnAccountTimestampIsConflictNotStaleLostRace() {
        var event = TestFixtures.event(1_000L, "100.00");
        var other = new TransactionId(UUID.randomUUID());
        when(processedTransactionPort.findOutcome(event.transactionId())).thenReturn(Optional.empty());
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(other));
        when(snapshotPort.findByAccountId(event.accountId())).thenReturn(Optional.empty());
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class)))
                .thenReturn(ClaimResult.ACCOUNT_TIMESTAMP_TAKEN)
                .thenReturn(ClaimResult.INSERTED);

        var result = useCase.ingest(event, "attempt-unique", "corr-unique");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
        assertThat(result.reasonCode()).isEqualTo("EQUAL_TIMESTAMP_CONFLICT");
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void uniqueViolationWithoutVisibleOccupantStillIsolatesAsConflict() {
        var event = TestFixtures.event(1_000L, "100.00");
        when(processedTransactionPort.findOutcome(event.transactionId())).thenReturn(Optional.empty());
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.empty());
        when(snapshotPort.findByAccountId(event.accountId())).thenReturn(Optional.empty());
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class)))
                .thenReturn(ClaimResult.ACCOUNT_TIMESTAMP_TAKEN)
                .thenReturn(ClaimResult.INSERTED);

        var result = useCase.ingest(event, "attempt-orphan", "corr-orphan");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
        assertThat(result.reasonCode()).isEqualTo("EQUAL_TIMESTAMP_CONFLICT");
        verify(orderingConflictPort, never()).recordConflict(any());
        verify(snapshotPort, never()).upsertIfNewer(any());
    }
}
