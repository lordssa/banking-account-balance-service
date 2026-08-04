package com.itau.account.adapter.out.persistence.jpa;

import com.itau.account.adapter.out.persistence.entity.AccountBalanceSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountBalanceSnapshotJpaRepository extends JpaRepository<AccountBalanceSnapshotEntity, UUID> {
}
