package com.itau.account.adapter.in.http.error;

import com.itau.account.application.exception.AccountNotFoundException;
import com.itau.account.application.exception.JournalAccessDeniedException;
import com.itau.account.domain.AccountId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RestExceptionHandlerTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void badRequestUsesMessageOrDefault() {
        MDC.put("correlationId", "c-1");
        var withMessage = handler.badRequest(new IllegalArgumentException("bad id"));
        assertThat(withMessage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withMessage.getBody().message()).isEqualTo("bad id");
        assertThat(withMessage.getBody().correlationId()).isEqualTo("c-1");

        var blank = handler.badRequest(new IllegalArgumentException(" "));
        assertThat(blank.getBody().message()).isEqualTo("Identificador inválido");
    }

    @Test
    void notFoundAndForbiddenAndUnexpected() {
        var notFound = handler.notFound(new AccountNotFoundException(AccountId.parse(UUID.randomUUID().toString())));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        var forbidden = handler.journalForbidden(new JournalAccessDeniedException());
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var unexpected = handler.unexpected(new RuntimeException("boom"));
        assertThat(unexpected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpected.getBody().correlationId()).isEqualTo("desconhecido");
    }
}
