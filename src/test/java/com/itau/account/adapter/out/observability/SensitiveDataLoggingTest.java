package com.itau.account.adapter.out.observability;

import com.itau.account.adapter.in.http.error.ApiErrorResponse;
import com.itau.account.adapter.in.http.error.RestExceptionHandler;
import com.itau.account.application.exception.AccountNotFoundException;
import com.itau.account.domain.AccountId;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataLoggingTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void errorBodiesDoNotIncludeBalanceOrPayload() {
        ResponseEntity<ApiErrorResponse> notFound = handler.notFound(
                new AccountNotFoundException(new AccountId(UUID.randomUUID())));
        assertThat(notFound.getBody().message().toLowerCase()).doesNotContain("balance", "amount", "payload");

        ResponseEntity<ApiErrorResponse> badRequest = handler.badRequest(new IllegalArgumentException("bad uuid"));
        assertThat(badRequest.getBody().message().toLowerCase()).doesNotContain("balance", "amount", "payload");
    }
}
