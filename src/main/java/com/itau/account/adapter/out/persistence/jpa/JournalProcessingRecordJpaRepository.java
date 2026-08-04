package com.itau.account.adapter.out.persistence.jpa;

import com.itau.account.adapter.out.persistence.entity.JournalProcessingRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JournalProcessingRecordJpaRepository extends JpaRepository<JournalProcessingRecordEntity, UUID> {

    List<JournalProcessingRecordEntity> findByTransactionIdOrderByReceivedAtAsc(UUID transactionId);

    List<JournalProcessingRecordEntity> findByAccountIdOrderByReceivedAtAsc(UUID accountId);
}
