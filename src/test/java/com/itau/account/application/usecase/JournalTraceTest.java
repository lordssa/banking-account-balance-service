package com.itau.account.application.usecase;

import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.JournalRecord;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalTraceTest {

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
    void acceptedEventJournalsSnapshotUpdateDecision() {
        var event = TestFixtures.event(5_000L, "333.00");
        when(processedTransactionPort.findOutcome(event.transactionId())).thenReturn(Optional.empty());
        when(processedTransactionPort.findOtherTransactionAt(any(), any(), any())).thenReturn(Optional.empty());
        when(snapshotPort.findByAccountId(event.accountId())).thenReturn(Optional.empty());
        when(processedTransactionPort.tryInsert(any(ProcessedTransactionInsert.class))).thenReturn(ClaimResult.INSERTED);
        when(snapshotPort.upsertIfNewer(event)).thenReturn(true);

        var result = useCase.ingest(event, "trace-1", "corr-trace");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.ACCEPTED);
        ArgumentCaptor<JournalRecord> captor = ArgumentCaptor.forClass(JournalRecord.class);
        verify(journalPort).append(captor.capture());
        JournalRecord journal = captor.getValue();
        assertThat(journal.outcome()).isEqualTo(ProcessingOutcome.ACCEPTED);
        assertThat(journal.snapshotEffect()).isEqualTo(SnapshotEffect.UPDATED);
        assertThat(journal.transactionId()).isEqualTo(event.transactionId());
        assertThat(journal.accountId()).isEqualTo(event.accountId());
        assertThat(journal.sourceTimestamp()).isEqualTo(event.sourceTimestamp());
        assertThat(journal.attemptKey()).isEqualTo("trace-1");
        assertThat(journal.decisionContext()).containsEntry("reasonCode", "ACCEPTED_NEWER");
    }
}
