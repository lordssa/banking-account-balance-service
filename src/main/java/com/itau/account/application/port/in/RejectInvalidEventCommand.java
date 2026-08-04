package com.itau.account.application.port.in;

import com.itau.account.domain.IngestResult;

public interface RejectInvalidEventCommand {
    IngestResult reject(String attemptKey, String correlationId, String reasonCode);
}
