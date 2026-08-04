package com.itau.account.domain;

import java.util.Objects;
import java.util.UUID;

public record AccountId(UUID value) {
    public AccountId {
        Objects.requireNonNull(value, "accountId é obrigatório");
    }

    public static AccountId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("accountId é obrigatório");
        }
        try {
            return new AccountId(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("accountId deve ser um UUID válido", ex);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
