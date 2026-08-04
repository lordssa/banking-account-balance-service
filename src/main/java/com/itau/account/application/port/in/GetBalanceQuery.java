package com.itau.account.application.port.in;

import com.itau.account.application.model.BalanceView;
import com.itau.account.domain.AccountId;

public interface GetBalanceQuery {
    BalanceView getBalance(AccountId accountId);
}
