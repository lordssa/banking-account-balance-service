package com.itau.account.adapter.out.persistence;

import com.itau.account.application.model.AdministrativeJournalActionInsert;
import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.model.OrderingConflictInsert;
import com.itau.account.application.model.ProcessedTransactionInsert;
import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.bootstrap.AccountApplication;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.TransactionId;
import com.itau.account.support.PostgresITSupport;
import com.itau.account.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AccountApplication.class)
class PersistenceAdaptersIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Autowired IngestBalanceEventCommand ingestCommand;
    @Autowired RejectInvalidEventCommand rejectInvalidCommand;
    @Autowired AccountBalanceSnapshotPort snapshotPort;
    @Autowired ProcessedTransactionPort processedTransactionPort;
    @Autowired JournalPort journalPort;
    @Autowired OrderingConflictPort orderingConflictPort;
    @Autowired AdministrativeJournalActionPort administrativeActions;

    @Test
    void ingestPersistsSnapshotProcessedAndJournal() {
        var event = TestFixtures.event(9_000_000L, "55.50");
        var result = ingestCommand.ingest(event, "persist-" + UUID.randomUUID(), "corr-persist");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.ACCEPTED);
        assertThat(snapshotPort.findByAccountId(event.accountId())).isPresent();
        assertThat(processedTransactionPort.findOutcome(event.transactionId()))
                .contains(ProcessingOutcome.ACCEPTED);

        List<JournalRecord> rows = journalPort.findByTransactionId(event.transactionId());
        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst().outcome()).isEqualTo(ProcessingOutcome.ACCEPTED);
        assertThat(journalPort.findByAccountId(event.accountId())).isNotEmpty();
    }

    @Test
    void invalidRejectionDoesNotCreateSnapshot() {
        UUID account = UUID.randomUUID();
        rejectInvalidCommand.reject("invalid-" + UUID.randomUUID(), "corr-inv", "INVALID_PAYLOAD");
        assertThat(snapshotPort.findByAccountId(new AccountId(account))).isEmpty();
    }

    @Test
    void orderingConflictRoundTrip() {
        var event = TestFixtures.event(8_000_000L, "1.00");
        ingestCommand.ingest(event, "oc-" + UUID.randomUUID(), "corr-oc");

        orderingConflictPort.recordConflict(OrderingConflictInsert.between(
                event.accountId(),
                event.sourceTimestamp(),
                event.transactionId(),
                new TransactionId(UUID.randomUUID())
        ));

        assertThat(orderingConflictPort.findOpen(event.accountId(), event.sourceTimestamp())).isPresent();
    }

    @Test
    void duplicateClaimReturnsDuplicateOutcome() {
        var event = TestFixtures.event(7_000_000L, "2.00");
        var insert = new ProcessedTransactionInsert(
                event.transactionId(),
                event.accountId(),
                event.sourceTimestamp(),
                ProcessingOutcome.ACCEPTED
        );
        assertThat(processedTransactionPort.tryInsert(insert)).isEqualTo(ClaimResult.INSERTED);
        assertThat(processedTransactionPort.tryInsert(insert)).isEqualTo(ClaimResult.DUPLICATE_TRANSACTION);
    }

    @Test
    void equalAccountTimestampClaimIsTaken() {
        UUID account = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        long micros = 7_100_000L;
        var first = TestFixtures.event(account, owner, UUID.randomUUID(), micros, "1.00");
        var second = TestFixtures.event(account, owner, UUID.randomUUID(), micros, "2.00");
        assertThat(processedTransactionPort.tryInsert(new ProcessedTransactionInsert(
                first.transactionId(), first.accountId(), first.sourceTimestamp(), ProcessingOutcome.ACCEPTED
        ))).isEqualTo(ClaimResult.INSERTED);
        assertThat(processedTransactionPort.tryInsert(new ProcessedTransactionInsert(
                second.transactionId(), second.accountId(), second.sourceTimestamp(), ProcessingOutcome.ACCEPTED
        ))).isEqualTo(ClaimResult.ACCOUNT_TIMESTAMP_TAKEN);
        assertThat(processedTransactionPort.findOtherTransactionAt(
                second.accountId(), second.sourceTimestamp(), second.transactionId()
        )).contains(first.transactionId());
    }

    @Test
    void administrativeActionCanBeRecorded() {
        administrativeActions.record(AdministrativeJournalActionInsert.of(
                AdministrativeJournalActionInsert.TYPE_JOURNAL_READ_BY_ACCOUNT,
                "subject-1",
                Map.of("accountId", UUID.randomUUID().toString()),
                AdministrativeJournalActionInsert.RESULT_DENIED
        ));
    }
}
