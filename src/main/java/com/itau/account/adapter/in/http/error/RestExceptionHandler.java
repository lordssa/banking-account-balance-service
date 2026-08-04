package com.itau.account.adapter.in.http.error;

import com.itau.account.application.exception.AccountNotFoundException;
import com.itau.account.application.exception.JournalAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Identificador inválido"
                : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", message, correlationId()));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> notFound(AccountNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("ACCOUNT_NOT_FOUND", "Conta não encontrada", correlationId()));
    }

    @ExceptionHandler(JournalAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> journalForbidden(JournalAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse("JOURNAL_ACCESS_DENIED", "Acesso ao journal negado", correlationId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception ex) {
        log.error("Falha inesperada correlationId={}", correlationId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_ERROR", "Falha inesperada", correlationId()));
    }

    private static String correlationId() {
        String fromMdc = MDC.get("correlationId");
        return fromMdc == null || fromMdc.isBlank() ? "desconhecido" : fromMdc;
    }
}
