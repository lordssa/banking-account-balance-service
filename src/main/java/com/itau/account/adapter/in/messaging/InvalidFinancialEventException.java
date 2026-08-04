package com.itau.account.adapter.in.messaging;

/**
 * Lançada quando o corpo da mensagem SQS não pode ser mapeado para um {@link com.itau.account.domain.BalanceEvent}.
 * Distinta de {@link IllegalArgumentException}s de domínio/aplicação, que devem permanecer retentáveis.
 */
public final class InvalidFinancialEventException extends RuntimeException {

    public InvalidFinancialEventException(String message) {
        super(message);
    }

    public InvalidFinancialEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
