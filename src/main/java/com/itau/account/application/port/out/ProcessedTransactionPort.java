package com.itau.account.application.port.out;

import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.ProcessedTransactionInsert;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.TransactionId;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ProcessedTransactionPort {
    Optional<ProcessingOutcome> findOutcome(TransactionId transactionId);

    Optional<TransactionId> findOtherTransactionAt(AccountId accountId, LocalDateTime sourceTimestamp, TransactionId exclude);

    /**
     * Attempts to claim processing for a transaction.
     * Distinguishes duplicate transaction id from equal-timestamp ownership races.
     */
    ClaimResult tryInsert(ProcessedTransactionInsert insert);
}
