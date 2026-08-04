package com.itau.account.application.port.in;

import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.IngestResult;

public interface IngestBalanceEventCommand {
    IngestResult ingest(BalanceEvent event, String attemptKey, String correlationId);
}
