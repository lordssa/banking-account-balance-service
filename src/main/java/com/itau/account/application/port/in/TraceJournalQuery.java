package com.itau.account.application.port.in;

import com.itau.account.application.model.JournalIngestSpan;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;

import java.time.Instant;
import java.util.List;

public interface TraceJournalQuery {
    List<JournalRecord> byTransaction(String subjectId, TransactionId transactionId);

    List<JournalRecord> byAccount(String subjectId, AccountId accountId);

    JournalIngestSpan ingestSpan(String subjectId, Instant since, List<AccountId> accountIds);
}
