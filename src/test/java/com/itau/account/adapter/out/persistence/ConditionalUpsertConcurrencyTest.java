package com.itau.account.adapter.out.persistence;

import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AccountApplication.class)
class ConditionalUpsertConcurrencyTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Autowired IngestBalanceEventCommand ingestCommand;
    @Autowired AccountBalanceSnapshotPort snapshotPort;

    @Test
    void concurrentNewerEventsPreserveLatestTimestampWinner() throws Exception {
        UUID account = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        BalanceEvent older = TestFixtures.event(account, owner, UUID.randomUUID(), 1_000L, "100.00");
        BalanceEvent newer = TestFixtures.event(account, owner, UUID.randomUUID(), 2_000L, "200.00");

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<IngestResult>> tasks = List.of(
                    () -> ingestCommand.ingest(older, "c-old-" + UUID.randomUUID(), "corr-old"),
                    () -> ingestCommand.ingest(newer, "c-new-" + UUID.randomUUID(), "corr-new")
            );
            List<Future<IngestResult>> futures = pool.invokeAll(tasks);
            List<IngestResult> results = new ArrayList<>();
            for (Future<IngestResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).allMatch(r ->
                    r.outcome() == ProcessingOutcome.ACCEPTED || r.outcome() == ProcessingOutcome.STALE);

            var snapshot = snapshotPort.findByAccountId(new AccountId(account));
            assertThat(snapshot).isPresent();
            assertThat(snapshot.get().sourceTimestamp()).isEqualTo(BalanceEvent.fromEpochMicros(2_000L));
            assertThat(snapshot.get().winningTransactionId()).isEqualTo(newer.transactionId());
            assertThat(snapshot.get().balance().amountPlainString()).isEqualTo("200");
        }
    }
}
