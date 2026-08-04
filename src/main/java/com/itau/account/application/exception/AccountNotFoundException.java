package com.itau.account.application.exception;

import com.itau.account.domain.AccountId;

public class AccountNotFoundException extends RuntimeException {
    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("Conta não encontrada");
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
