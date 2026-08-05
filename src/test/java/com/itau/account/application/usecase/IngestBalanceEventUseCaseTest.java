package com.itau.account.application.usecase;

import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.model.OrderingConflictInsert;
import com.itau.account.application.model.ProcessedTransactionInsert;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.Money;
import com.itau.account.domain.OwnerId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.domain.TransactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestBalanceEventUseCaseTest {

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
    void acceptsNewerEventWithClaimFirstPath() {
        var event = sampleEvent(1_000L, "100.00");
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);
        when(snapshotPort.upsertIfNewer(event)).thenReturn(true);

        var result = useCase.ingest(event, "attempt-1", "corr-1");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.ACCEPTED);
        assertThat(result.snapshotEffect()).isEqualTo(SnapshotEffect.UPDATED);
        verify(processedTransactionPort, never()).findOutcome(any());
        verify(processedTransactionPort, never()).findOtherTransactionAt(any(), any(), any());
        verify(snapshotPort, never()).findByAccountId(any());

        ArgumentCaptor<JournalRecord> journalCaptor = ArgumentCaptor.forClass(JournalRecord.class);
        verify(journalPort).append(journalCaptor.capture());
        assertThat(journalCaptor.getValue().outcome()).isEqualTo(ProcessingOutcome.ACCEPTED);
        assertThat(journalCaptor.getValue().attemptKey()).isEqualTo("attempt-1");
    }

    @Test
    void duplicateDoesNotUpdate() {
        var event = sampleEvent(1_000L, "100.00");
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class)))
                .thenReturn(ClaimResult.DUPLICATE_TRANSACTION);

        var result = useCase.ingest(event, "attempt-2", "corr-2");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.DUPLICATE);
        assertThat(result.snapshotEffect()).isEqualTo(SnapshotEffect.UNCHANGED);
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void staleOlderDoesNotOverwrite() {
        var older = sampleEvent(1_000L, "50.00");
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);
        when(snapshotPort.upsertIfNewer(older)).thenReturn(false);

        var result = useCase.ingest(older, "attempt-3", "corr-3");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.STALE);
        assertThat(result.reasonCode()).isEqualTo("STALE_LOST_RACE");
    }

    @Test
    void equalTimestampConflictsWhenOtherAlreadyVisible() {
        var event = sampleEvent(1_000L, "100.00");
        var other = new TransactionId(UUID.randomUUID());
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class)))
                .thenReturn(ClaimResult.ACCOUNT_TIMESTAMP_TAKEN)
                .thenReturn(ClaimResult.INSERTED);
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.of(other));

        var result = useCase.ingest(event, "attempt-4", "corr-4");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
        ArgumentCaptor<OrderingConflictInsert> conflictCaptor = ArgumentCaptor.forClass(OrderingConflictInsert.class);
        verify(orderingConflictPort).recordConflict(conflictCaptor.capture());
        assertThat(conflictCaptor.getValue().transactionIdA()).isEqualTo(other);
        assertThat(conflictCaptor.getValue().transactionIdB()).isEqualTo(event.transactionId());
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void concurrentEqualTimestampUniqueViolationBecomesConflictNotStaleLostRace() {
        var event = sampleEvent(1_000L, "100.00");
        var other = new TransactionId(UUID.randomUUID());
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class)))
                .thenReturn(ClaimResult.ACCOUNT_TIMESTAMP_TAKEN)
                .thenReturn(ClaimResult.INSERTED);
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.of(other));

        var result = useCase.ingest(event, "attempt-5", "corr-5");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
        assertThat(result.reasonCode()).isEqualTo("EQUAL_TIMESTAMP_CONFLICT");
        verify(orderingConflictPort).recordConflict(any(OrderingConflictInsert.class));
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void lostCasRaceIsStale() {
        var event = sampleEvent(3_000L, "100.00");
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);
        when(snapshotPort.upsertIfNewer(event)).thenReturn(false);

        var result = useCase.ingest(event, "attempt-6", "corr-6");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.STALE);
        assertThat(result.reasonCode()).isEqualTo("STALE_LOST_RACE");
    }

    private static BalanceEvent sampleEvent(long micros, String balance) {
        return new BalanceEvent(
                new TransactionId(UUID.randomUUID()),
                "CREDIT",
                Money.of(new BigDecimal("10.00"), "BRL"),
                "APPROVED",
                BalanceEvent.fromEpochMicros(micros),
                new AccountId(UUID.randomUUID()),
                new OwnerId(UUID.randomUUID()),
                Instant.now(),
                "ENABLED",
                Money.of(new BigDecimal(balance), "BRL"),
                Instant.now()
        );
    }
}
