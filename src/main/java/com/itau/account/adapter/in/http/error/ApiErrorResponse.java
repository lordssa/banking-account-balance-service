package com.itau.account.adapter.in.http.error;

public record ApiErrorResponse(String code, String message, String correlationId) {
}
