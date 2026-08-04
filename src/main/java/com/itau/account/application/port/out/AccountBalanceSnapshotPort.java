package com.itau.account.application.port.out;

import com.itau.account.domain.AccountBalanceSnapshot;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;

import java.util.Optional;

public interface AccountBalanceSnapshotPort {
    Optional<AccountBalanceSnapshot> findByAccountId(AccountId accountId);

    /**
     * Atomic insert-or-update only when incoming source timestamp is strictly newer.
     * @return true if snapshot inserted or updated
     */
    boolean upsertIfNewer(BalanceEvent event);
}
