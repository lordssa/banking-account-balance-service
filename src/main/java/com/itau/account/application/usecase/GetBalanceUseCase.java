package com.itau.account.application.usecase;

import com.itau.account.application.exception.AccountNotFoundException;
import com.itau.account.application.model.BalanceView;
import com.itau.account.application.port.in.GetBalanceQuery;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.domain.AccountId;

public class GetBalanceUseCase implements GetBalanceQuery {

    private final AccountBalanceSnapshotPort snapshotPort;

    public GetBalanceUseCase(AccountBalanceSnapshotPort snapshotPort) {
        this.snapshotPort = snapshotPort;
    }

    @Override
    public BalanceView getBalance(AccountId accountId) {
        return snapshotPort.findByAccountId(accountId)
                .map(s -> new BalanceView(s.accountId(), s.ownerId(), s.balance(), s.sourceTimestamp()))
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
