package com.itau.account.bootstrap;

import com.itau.account.application.port.in.GetBalanceQuery;
import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.application.port.in.RequestJournalReplayCommand;
import com.itau.account.application.port.in.TraceJournalQuery;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalAccessPolicy;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.application.usecase.GetBalanceUseCase;
import com.itau.account.application.usecase.IngestBalanceEventUseCase;
import com.itau.account.application.usecase.RejectInvalidEventUseCase;
import com.itau.account.application.usecase.RequestJournalReplayUseCase;
import com.itau.account.application.usecase.TraceJournalUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ApplicationConfig {

    @Bean
    GetBalanceQuery getBalanceQuery(AccountBalanceSnapshotPort snapshotPort) {
        return new GetBalanceUseCase(snapshotPort);
    }

    @Bean
    IngestBalanceEventCommand ingestBalanceEventCommand(
            AccountBalanceSnapshotPort snapshotPort,
            ProcessedTransactionPort processedTransactionPort,
            JournalPort journalPort,
            OrderingConflictPort orderingConflictPort,
            TransactionTemplate transactionTemplate
    ) {
        IngestBalanceEventUseCase useCase = new IngestBalanceEventUseCase(
                snapshotPort, processedTransactionPort, journalPort, orderingConflictPort);
        return (event, attemptKey, correlationId) ->
                transactionTemplate.execute(status -> useCase.ingest(event, attemptKey, correlationId));
    }

    @Bean
    RejectInvalidEventCommand rejectInvalidEventCommand(
            JournalPort journalPort,
            TransactionTemplate transactionTemplate
    ) {
        RejectInvalidEventUseCase useCase = new RejectInvalidEventUseCase(journalPort);
        return (attemptKey, correlationId, reasonCode) ->
                transactionTemplate.execute(status -> useCase.reject(attemptKey, correlationId, reasonCode));
    }

    @Bean
    TraceJournalQuery traceJournalQuery(
            JournalPort journalPort,
            JournalAccessPolicy accessPolicy,
            AdministrativeJournalActionPort administrativeActions
    ) {
        return new TraceJournalUseCase(journalPort, accessPolicy, administrativeActions);
    }

    @Bean
    RequestJournalReplayCommand requestJournalReplayCommand(
            JournalAccessPolicy accessPolicy,
            AdministrativeJournalActionPort administrativeActions
    ) {
        return new RequestJournalReplayUseCase(accessPolicy, administrativeActions);
    }
}
