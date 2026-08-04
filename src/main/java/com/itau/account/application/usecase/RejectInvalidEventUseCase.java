package com.itau.account.application.usecase;

import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class RejectInvalidEventUseCase implements RejectInvalidEventCommand {

    private final JournalPort journalPort;

    public RejectInvalidEventUseCase(JournalPort journalPort) {
        this.journalPort = journalPort;
    }

    @Override
    public IngestResult reject(String attemptKey, String correlationId, String reasonCode) {
        ProcessingOutcome outcome = "RETRY_EXHAUSTED".equals(reasonCode)
                ? ProcessingOutcome.PERMANENTLY_FAILED
                : ProcessingOutcome.INVALID;
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("reasonCode", reasonCode);
        journalPort.append(JournalRecord.create(
                null,
                null,
                null,
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
