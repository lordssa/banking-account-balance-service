package com.itau.account.application.port.out;

import com.itau.account.application.model.OrderingConflictInsert;
import com.itau.account.application.model.OrderingConflictRecord;
import com.itau.account.domain.AccountId;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OrderingConflictPort {
    Optional<OrderingConflictRecord> findOpen(AccountId accountId, LocalDateTime sourceTimestamp);

    void recordConflict(OrderingConflictInsert conflict);
}
