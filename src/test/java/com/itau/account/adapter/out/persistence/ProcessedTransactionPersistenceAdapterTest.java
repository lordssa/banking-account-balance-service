package com.itau.account.adapter.out.persistence;

import com.itau.account.application.model.ClaimResult;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedTransactionPersistenceAdapterTest {

    @Test
    void classifiesAccountTimestampUniqueAsTaken() {
        var ex = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"uq_processed_account_source_ts\"");
        assertThat(ProcessedTransactionPersistenceAdapter.classify(ex))
                .isEqualTo(ClaimResult.ACCOUNT_TIMESTAMP_TAKEN);
    }

    @Test
    void classifiesPrimaryKeyAsDuplicate() {
        var ex = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"processed_transaction_pkey\"");
        assertThat(ProcessedTransactionPersistenceAdapter.classify(ex))
                .isEqualTo(ClaimResult.DUPLICATE_TRANSACTION);
    }
}
