package com.itau.account.adapter.in.messaging;

import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.bootstrap.AccountApplication;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.support.PostgresITSupport;
import com.itau.account.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AccountApplication.class)
class InvalidEventIsolationIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Autowired IngestBalanceEventCommand ingestCommand;
    @Autowired RejectInvalidEventCommand rejectInvalidCommand;
    @Autowired AccountBalanceSnapshotPort snapshotPort;

    @Test
    void invalidEventDoesNotChangeOtherAccountSnapshot() {
        var valid = TestFixtures.event(6_500_000L, "77.00");
        var accepted = ingestCommand.ingest(valid, "ok-" + UUID.randomUUID(), "corr-ok");
        assertThat(accepted.outcome()).isEqualTo(ProcessingOutcome.ACCEPTED);

        rejectInvalidCommand.reject("bad-" + UUID.randomUUID(), "corr-bad", "INVALID_PAYLOAD");

        var snapshot = snapshotPort.findByAccountId(valid.accountId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().balance().amountPlainString()).isEqualTo("77");
        assertThat(snapshotPort.findByAccountId(new AccountId(UUID.randomUUID()))).isEmpty();
    }
}
