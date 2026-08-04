package com.itau.account.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record CurrencyCode(String value) {
    private static final Pattern ISO = Pattern.compile("^[A-Z]{3}$");

    public CurrencyCode {
        Objects.requireNonNull(value, "moeda é obrigatória");
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!ISO.matcher(value).matches()) {
            throw new IllegalArgumentException("moeda deve ser ISO 4217 (3 letras)");
        }
    }

    public static CurrencyCode of(String raw) {
        return new CurrencyCode(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
