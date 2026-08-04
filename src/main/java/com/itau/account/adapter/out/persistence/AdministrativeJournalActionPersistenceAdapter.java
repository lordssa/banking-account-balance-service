package com.itau.account.adapter.out.persistence;

import com.itau.account.adapter.out.persistence.entity.AdministrativeJournalActionEntity;
import com.itau.account.adapter.out.persistence.jpa.AdministrativeJournalActionJpaRepository;
import com.itau.account.application.model.AdministrativeJournalActionInsert;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class AdministrativeJournalActionPersistenceAdapter implements AdministrativeJournalActionPort {

    private final AdministrativeJournalActionJpaRepository repository;
    private final JsonMapper jsonMapper;

    public AdministrativeJournalActionPersistenceAdapter(
            AdministrativeJournalActionJpaRepository repository,
            JsonMapper jsonMapper
    ) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdministrativeJournalActionInsert action) {
        repository.saveAndFlush(AdministrativeJournalActionEntity.create(
                action.actionId(),
                action.actionType(),
                action.actorId(),
                toJson(action.scope()),
                action.result()
        ));
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Não foi possível serializar o escopo da ação administrativa", e);
        }
    }
}
