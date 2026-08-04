package com.itau.account.application.model;

import com.itau.account.domain.AccountId;
import com.itau.account.domain.Money;
import com.itau.account.domain.OwnerId;

import java.time.LocalDateTime;


public record BalanceView(
        AccountId accountId,
        OwnerId ownerId,
        Money balance,
        LocalDateTime lastUpdatedAt
) {
}
