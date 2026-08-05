package com.itau.account.application.usecase;

import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.domain.TransactionId;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class RejectInvalidEventUseCase implements RejectInvalidEventCommand {

    private final JournalPort journalPort;

    public RejectInvalidEventUseCase(JournalPort journalPort) {
        this.journalPort = journalPort;
    }

    @Override
    public IngestResult reject(String attemptKey, String correlationId, String reasonCode) {
        return reject(attemptKey, correlationId, reasonCode, Map.of(), null);
    }

    @Override
    public IngestResult reject(
            String attemptKey,
            String correlationId,
            String reasonCode,
            Map<String, Object> transportContext,
            BalanceEvent parsedEventOrNull
    ) {
        ProcessingOutcome outcome = "RETRY_EXHAUSTED".equals(reasonCode)
                ? ProcessingOutcome.PERMANENTLY_FAILED
                : ProcessingOutcome.INVALID;
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("reasonCode", reasonCode);
        if (transportContext != null) {
            ctx.putAll(transportContext);
        }

        TransactionId transactionId = null;
        AccountId accountId = null;
        LocalDateTime sourceTimestamp = null;
        if (parsedEventOrNull != null) {
            transactionId = parsedEventOrNull.transactionId();
            accountId = parsedEventOrNull.accountId();
            sourceTimestamp = parsedEventOrNull.sourceTimestamp();
        }

        journalPort.append(JournalRecord.create(
                transactionId,
                accountId,
                sourceTimestamp,
                Instant.now(),
                outcome,
                SnapshotEffect.NOT_APPLICABLE,
                correlationId,
                attemptKey,
                ctx
        ));
        return IngestResult.of(outcome, SnapshotEffect.NOT_APPLICABLE, reasonCode);
    }
}
