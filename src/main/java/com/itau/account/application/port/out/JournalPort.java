package com.itau.account.application.port.out;

import com.itau.account.application.model.JournalRecord;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;

import java.util.List;

public interface JournalPort {
    void append(JournalRecord record);

    List<JournalRecord> findByTransactionId(TransactionId transactionId);

    List<JournalRecord> findByAccountId(AccountId accountId);
}
