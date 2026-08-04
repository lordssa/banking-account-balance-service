package com.itau.account.adapter.out.persistence.jpa;

import com.itau.account.adapter.out.persistence.entity.OrderingConflictEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OrderingConflictJpaRepository extends JpaRepository<OrderingConflictEntity, UUID> {

    Optional<OrderingConflictEntity> findByAccountIdAndSourceTimestamp(UUID accountId, LocalDateTime sourceTimestamp);
}
