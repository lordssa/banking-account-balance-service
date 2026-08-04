package com.itau.account.domain;

import java.util.Objects;
import java.util.UUID;

public record TransactionId(UUID value) {
    public TransactionId {
        Objects.requireNonNull(value, "transactionId é obrigatório");
    }

    public static TransactionId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("transactionId é obrigatório");
        }
        try {
            return new TransactionId(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("transactionId deve ser um UUID válido", ex);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
