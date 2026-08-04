package com.itau.account.domain;

import java.util.Objects;
import java.util.UUID;

public record OwnerId(UUID value) {
    public OwnerId {
        Objects.requireNonNull(value, "ownerId é obrigatório");
    }

    public static OwnerId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("ownerId é obrigatório");
        }
        try {
            return new OwnerId(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("ownerId deve ser um UUID válido", ex);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
