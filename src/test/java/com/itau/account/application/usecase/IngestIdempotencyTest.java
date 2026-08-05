package com.itau.account.application.usecase;

import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.ProcessedTransactionInsert;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestIdempotencyTest {

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
    void duplicateTransactionDoesNotUpdateSnapshot() {
        var event = TestFixtures.event(1_000L, "100.00");
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class)))
                .thenReturn(ClaimResult.DUPLICATE_TRANSACTION);

        var result = useCase.ingest(event, "attempt-dup", "corr-dup");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.DUPLICATE);
        assertThat(result.snapshotEffect()).isEqualTo(SnapshotEffect.UNCHANGED);
        verify(snapshotPort, never()).upsertIfNewer(any());
    }

    @Test
    void concurrentDuplicateClaimIsDuplicate() {
        var event = TestFixtures.event(1_000L, "100.00");
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class)))
                .thenReturn(ClaimResult.DUPLICATE_TRANSACTION);

        var result = useCase.ingest(event, "attempt-race", "corr-race");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.DUPLICATE);
        verify(snapshotPort, never()).upsertIfNewer(any());
    }
}
