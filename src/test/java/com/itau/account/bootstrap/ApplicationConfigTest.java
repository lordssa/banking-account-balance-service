package com.itau.account.bootstrap;

import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalAccessPolicy;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationConfigTest {

    @Mock AccountBalanceSnapshotPort snapshotPort;
    @Mock ProcessedTransactionPort processedTransactionPort;
    @Mock JournalPort journalPort;
    @Mock OrderingConflictPort orderingConflictPort;
    @Mock AdministrativeJournalActionPort administrativeJournalActionPort;
    @Mock JournalAccessPolicy journalAccessPolicy;
    @Mock TransactionTemplate transactionTemplate;

    @Test
    void rejectCommandCoversBothOverloads() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });

        ApplicationConfig config = new ApplicationConfig();
        RejectInvalidEventCommand command = config.rejectInvalidEventCommand(journalPort, transactionTemplate);

        IngestResult threeArg = command.reject("a-1", "c-1", "INVALID_PAYLOAD");
        IngestResult fiveArg = command.reject("a-2", "c-2", "RETRY_EXHAUSTED", Map.of("messageId", "m"), null);

        assertThat(threeArg.outcome()).isEqualTo(ProcessingOutcome.INVALID);
        assertThat(fiveArg.outcome()).isEqualTo(ProcessingOutcome.PERMANENTLY_FAILED);
    }

    @Test
    void otherBeansAreCreated() {
        ApplicationConfig config = new ApplicationConfig();
        assertThat(config.getBalanceQuery(snapshotPort)).isNotNull();
        assertThat(config.ingestBalanceEventCommand(
                snapshotPort, processedTransactionPort, journalPort, orderingConflictPort, transactionTemplate))
                .isNotNull();
        assertThat(config.traceJournalQuery(journalPort, journalAccessPolicy, administrativeJournalActionPort))
                .isNotNull();
        assertThat(config.requestJournalReplayCommand(journalAccessPolicy, administrativeJournalActionPort))
                .isNotNull();
    }

    @Test
    void defaultRejectOverloadDelegates() {
        RejectInvalidEventCommand command = (attemptKey, correlationId, reasonCode) ->
                IngestResult.of(ProcessingOutcome.INVALID, SnapshotEffect.UNCHANGED, reasonCode);

        IngestResult result = command.reject("a", "c", "R", Map.of(), null);
        assertThat(result.reasonCode()).isEqualTo("R");
    }
}
