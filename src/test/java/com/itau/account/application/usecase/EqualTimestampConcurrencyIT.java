package com.itau.account.application.usecase;

import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.bootstrap.AccountApplication;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.support.PostgresITSupport;
import com.itau.account.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AccountApplication.class)
class EqualTimestampConcurrencyIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Autowired IngestBalanceEventCommand ingestCommand;
    @Autowired AccountBalanceSnapshotPort snapshotPort;
    @Autowired OrderingConflictPort orderingConflictPort;

    @Test
    void concurrentEqualTimestampEventsDoNotResolveByRace() throws Exception {
        UUID account = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        long micros = 1_700_000_000_000_100L;
        BalanceEvent a = TestFixtures.event(account, owner, UUID.randomUUID(), micros, "100.00");
        BalanceEvent b = TestFixtures.event(account, owner, UUID.randomUUID(), micros, "200.00");

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<IngestResult> fa = pool.submit(ingest(a, "a-" + UUID.randomUUID(), "corr-a"));
            Future<IngestResult> fb = pool.submit(ingest(b, "b-" + UUID.randomUUID(), "corr-b"));
            IngestResult ra = fa.get();
            IngestResult rb = fb.get();

            assertThat(ra.outcome()).isIn(ProcessingOutcome.ACCEPTED, ProcessingOutcome.CONFLICTING);
            assertThat(rb.outcome()).isIn(ProcessingOutcome.ACCEPTED, ProcessingOutcome.CONFLICTING);
            assertThat(ra.outcome()).isNotEqualTo(rb.outcome());
            assertThat(ra.reasonCode()).isNotEqualTo("STALE_LOST_RACE");
            assertThat(rb.reasonCode()).isNotEqualTo("STALE_LOST_RACE");

            var conflict = orderingConflictPort.findOpen(new AccountId(account), BalanceEvent.fromEpochMicros(micros));
            assertThat(conflict).isPresent();

            var snapshot = snapshotPort.findByAccountId(new AccountId(account));
            if (ra.outcome() == ProcessingOutcome.ACCEPTED) {
                assertThat(snapshot).isPresent();
                assertThat(snapshot.get().winningTransactionId()).isEqualTo(a.transactionId());
                assertThat(rb.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
            } else {
                assertThat(snapshot).isPresent();
                assertThat(snapshot.get().winningTransactionId()).isEqualTo(b.transactionId());
                assertThat(ra.outcome()).isEqualTo(ProcessingOutcome.CONFLICTING);
            }
        }
    }

    private Callable<IngestResult> ingest(BalanceEvent event, String attemptKey, String correlationId) {
        return () -> ingestCommand.ingest(event, attemptKey, correlationId);
    }
}
