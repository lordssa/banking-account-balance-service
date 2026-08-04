package com.itau.account.application.exception;

public class JournalAccessDeniedException extends RuntimeException {
    public JournalAccessDeniedException() {
        super("Acesso ao journal negado");
    }
}
