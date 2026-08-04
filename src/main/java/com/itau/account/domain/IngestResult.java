package com.itau.account.domain;

import java.util.Objects;

public record IngestResult(
        ProcessingOutcome outcome,
        SnapshotEffect snapshotEffect,
        String reasonCode
) {
    public IngestResult {
        Objects.requireNonNull(outcome, "outcome é obrigatório");
        Objects.requireNonNull(snapshotEffect, "snapshotEffect é obrigatório");
        Objects.requireNonNull(reasonCode, "reasonCode é obrigatório");
    }

    public static IngestResult of(ProcessingOutcome outcome, SnapshotEffect effect, String reasonCode) {
        return new IngestResult(outcome, effect, reasonCode);
    }
}
