package com.itau.account.application.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AdministrativeJournalActionInsert(
        UUID actionId,
        String actionType,
        String actorId,
        Map<String, Object> scope,
        String result
) {
    public static final String RESULT_DENIED = "DENIED";
    public static final String RESULT_ALLOWED = "ALLOWED";

    public static final String TYPE_JOURNAL_READ_BY_TRANSACTION = "JOURNAL_READ_BY_TRANSACTION";
    public static final String TYPE_JOURNAL_READ_BY_ACCOUNT = "JOURNAL_READ_BY_ACCOUNT";
    public static final String TYPE_JOURNAL_INGEST_SPAN = "JOURNAL_INGEST_SPAN";
    public static final String TYPE_JOURNAL_REPLAY = "JOURNAL_REPLAY";

    public AdministrativeJournalActionInsert {
        Objects.requireNonNull(actionId, "actionId é obrigatório");
        Objects.requireNonNull(actionType, "actionType é obrigatório");
        Objects.requireNonNull(actorId, "actorId é obrigatório");
        Objects.requireNonNull(scope, "scope é obrigatório");
        Objects.requireNonNull(result, "result é obrigatório");
        scope = Map.copyOf(scope);
    }

    public static AdministrativeJournalActionInsert of(
            String actionType,
            String actorId,
            Map<String, Object> scope,
            String result
    ) {
        return new AdministrativeJournalActionInsert(UUID.randomUUID(), actionType, actorId, scope, result);
    }
}
