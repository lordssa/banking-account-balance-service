package com.itau.account.application.usecase;

import com.itau.account.application.model.ClaimResult;
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
class IngestOutOfOrderTest {

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
    void olderEventIsStaleAndDoesNotOverwrite() {
        var older = TestFixtures.event(1_000L, "50.00");
        var current = new AccountBalanceSnapshot(
                older.accountId(),
                older.ownerId(),
                Money.of(new BigDecimal("90.00"), "BRL"),
                BalanceEvent.fromEpochMicros(2_000L),
                "ENABLED",
                new TransactionId(UUID.randomUUID())
        );
        when(processedTransactionPort.findOutcome(older.transactionId())).thenReturn(Optional.empty());
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.empty());
        when(snapshotPort.findByAccountId(older.accountId())).thenReturn(Optional.of(current));
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);

        var result = useCase.ingest(older, "attempt-stale", "corr-stale");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.STALE);
        assertThat(result.snapshotEffect()).isEqualTo(SnapshotEffect.UNCHANGED);
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void lostCasRaceIsStale() {
        var event = TestFixtures.event(3_000L, "120.00");
        when(processedTransactionPort.findOutcome(event.transactionId())).thenReturn(Optional.empty());
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.empty());
        when(snapshotPort.findByAccountId(event.accountId())).thenReturn(Optional.empty());
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);
        when(snapshotPort.upsertIfNewer(event)).thenReturn(false);

        var result = useCase.ingest(event, "attempt-lost", "corr-lost");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.STALE);
        assertThat(result.reasonCode()).isEqualTo("STALE_LOST_RACE");
    }
}
