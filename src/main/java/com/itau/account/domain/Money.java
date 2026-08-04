package com.itau.account.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Money(BigDecimal amount, CurrencyCode currency) {
    public Money {
        Objects.requireNonNull(amount, "valor é obrigatório");
        Objects.requireNonNull(currency, "moeda é obrigatória");
        if (amount.scale() < 0) {
            throw new IllegalArgumentException("a escala do valor deve ser >= 0");
        }
        amount = amount.stripTrailingZeros();
        if (amount.scale() < 0) {
            amount = amount.setScale(0);
        }
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, CurrencyCode.of(currency));
    }

    public static Money parse(String amount, String currency) {
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("valor é obrigatório");
        }
        try {
            return of(new BigDecimal(amount.trim()), currency);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("valor deve ser um decimal exato", ex);
        }
    }

    public String amountPlainString() {
        return amount.toPlainString();
    }
}
