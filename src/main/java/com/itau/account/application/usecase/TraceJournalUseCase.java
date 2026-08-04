package com.itau.account.application.usecase;

import com.itau.account.application.exception.JournalAccessDeniedException;
import com.itau.account.application.model.AdministrativeJournalActionInsert;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.port.in.TraceJournalQuery;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalAccessPolicy;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;

import java.util.List;
import java.util.Map;

public class TraceJournalUseCase implements TraceJournalQuery {

    private final JournalPort journalPort;
    private final JournalAccessPolicy accessPolicy;
    private final AdministrativeJournalActionPort administrativeActions;

    public TraceJournalUseCase(
            JournalPort journalPort,
            JournalAccessPolicy accessPolicy,
            AdministrativeJournalActionPort administrativeActions
    ) {
        this.journalPort = journalPort;
        this.accessPolicy = accessPolicy;
        this.administrativeActions = administrativeActions;
    }

    @Override
    public List<JournalRecord> byTransaction(String subjectId, TransactionId transactionId) {
        boolean allowed = accessPolicy.canReadJournal(subjectId);
        administrativeActions.record(AdministrativeJournalActionInsert.of(
                AdministrativeJournalActionInsert.TYPE_JOURNAL_READ_BY_TRANSACTION,
                subjectId,
                Map.of("transactionId", transactionId.toString()),
                allowed
                        ? AdministrativeJournalActionInsert.RESULT_ALLOWED
                        : AdministrativeJournalActionInsert.RESULT_DENIED
        ));
        if (!allowed) {
            throw new JournalAccessDeniedException();
        }
        return journalPort.findByTransactionId(transactionId);
    }

    @Override
    public List<JournalRecord> byAccount(String subjectId, AccountId accountId) {
        boolean allowed = accessPolicy.canReadJournal(subjectId);
        administrativeActions.record(AdministrativeJournalActionInsert.of(
                AdministrativeJournalActionInsert.TYPE_JOURNAL_READ_BY_ACCOUNT,
                subjectId,
                Map.of("accountId", accountId.toString()),
                allowed
                        ? AdministrativeJournalActionInsert.RESULT_ALLOWED
                        : AdministrativeJournalActionInsert.RESULT_DENIED
        ));
        if (!allowed) {
            throw new JournalAccessDeniedException();
        }
        return journalPort.findByAccountId(accountId);
    }
}
