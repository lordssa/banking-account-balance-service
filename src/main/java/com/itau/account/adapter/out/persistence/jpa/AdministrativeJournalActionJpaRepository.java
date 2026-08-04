package com.itau.account.adapter.out.persistence.jpa;

import com.itau.account.adapter.out.persistence.entity.AdministrativeJournalActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdministrativeJournalActionJpaRepository
        extends JpaRepository<AdministrativeJournalActionEntity, UUID> {
}
