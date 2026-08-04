package com.itau.account.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "administrative_journal_action")
public class AdministrativeJournalActionEntity {

    @Id
    @Column(name = "action_id", nullable = false)
    private UUID actionId;

    @Column(name = "action_type", nullable = false, length = 64)
    private String actionType;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope", nullable = false, columnDefinition = "jsonb")
    private String scope;

    @Column(name = "result", nullable = false, length = 32)
    private String result;

    protected AdministrativeJournalActionEntity() {
    }

    public static AdministrativeJournalActionEntity create(
            UUID actionId,
            String actionType,
            String actorId,
            String scopeJson,
            String result
    ) {
        var entity = new AdministrativeJournalActionEntity();
        entity.actionId = actionId;
        entity.actionType = actionType;
        entity.actorId = actorId;
        entity.scope = scopeJson;
        entity.result = result;
        return entity;
    }
}
