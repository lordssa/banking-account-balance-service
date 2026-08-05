package com.itau.account.application.port.in;

import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.IngestResult;

import java.util.Map;

@FunctionalInterface
public interface RejectInvalidEventCommand {

    IngestResult reject(String attemptKey, String correlationId, String reasonCode);

    default IngestResult reject(
            String attemptKey,
            String correlationId,
            String reasonCode,
            Map<String, Object> transportContext,
            BalanceEvent parsedEventOrNull
    ) {
        return reject(attemptKey, correlationId, reasonCode);
    }
}
