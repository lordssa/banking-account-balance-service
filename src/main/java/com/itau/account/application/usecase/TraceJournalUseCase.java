package com.itau.account.application.usecase;

import com.itau.account.application.exception.JournalAccessDeniedException;
import com.itau.account.application.model.AdministrativeJournalActionInsert;
import com.itau.account.application.model.JournalIngestSpan;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.port.in.TraceJournalQuery;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalAccessPolicy;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

    @Override
    public JournalIngestSpan ingestSpan(String subjectId, Instant since, List<AccountId> accountIds) {
        Objects.requireNonNull(since, "since é obrigatório");
        if (accountIds == null || accountIds.isEmpty()) {
            throw new IllegalArgumentException("ao menos um accountId é obrigatório");
        }
        if (accountIds.size() > 500) {
            throw new IllegalArgumentException("no máximo 500 accountIds por consulta de ingest-span");
        }

        boolean allowed = accessPolicy.canReadJournal(subjectId);
        administrativeActions.record(AdministrativeJournalActionInsert.of(
                AdministrativeJournalActionInsert.TYPE_JOURNAL_INGEST_SPAN,
                subjectId,
                Map.of(
                        "since", since.toString(),
                        "accountCount", accountIds.size()
                ),
                allowed
                        ? AdministrativeJournalActionInsert.RESULT_ALLOWED
                        : AdministrativeJournalActionInsert.RESULT_DENIED
        ));
        if (!allowed) {
            throw new JournalAccessDeniedException();
        }

        Instant min = null;
        Instant max = null;
        Set<UUID> transactionIds = new HashSet<>();
        for (AccountId accountId : accountIds) {
            for (JournalRecord record : journalPort.findByAccountId(accountId)) {
                if (record.transactionId() == null || !record.receivedAt().isAfter(since)) {
                    continue;
                }
                if (!transactionIds.add(record.transactionId().value())) {
                    continue;
                }
                Instant receivedAt = record.receivedAt();
                if (min == null || receivedAt.isBefore(min)) {
                    min = receivedAt;
                }
                if (max == null || receivedAt.isAfter(max)) {
                    max = receivedAt;
                }
            }
        }
        return JournalIngestSpan.of(transactionIds.size(), min, max);
    }
}
