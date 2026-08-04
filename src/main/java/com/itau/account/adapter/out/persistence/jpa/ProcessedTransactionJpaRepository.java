package com.itau.account.adapter.out.persistence.jpa;

import com.itau.account.adapter.out.persistence.entity.ProcessedTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedTransactionJpaRepository extends JpaRepository<ProcessedTransactionEntity, UUID> {

    Optional<ProcessedTransactionEntity> findFirstByAccountIdAndSourceTimestampAndTransactionIdNot(
            UUID accountId,
            LocalDateTime sourceTimestamp,
            UUID excludeTransactionId
    );
}
