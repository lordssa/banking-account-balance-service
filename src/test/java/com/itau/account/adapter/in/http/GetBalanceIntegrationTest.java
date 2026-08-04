package com.itau.account.adapter.in.http;

import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.bootstrap.AccountApplication;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.Money;
import com.itau.account.domain.OwnerId;
import com.itau.account.domain.TransactionId;
import com.itau.account.support.PostgresITSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AccountApplication.class)
@AutoConfigureMockMvc
class GetBalanceIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired AccountBalanceSnapshotPort snapshotPort;

    @Test
    void returnsBalanceForKnownAccount() throws Exception {
        UUID account = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID tx = UUID.randomUUID();
        var event = new BalanceEvent(
                new TransactionId(tx),
                "CREDIT",
                Money.of(new BigDecimal("10.00"), "BRL"),
                "APPROVED",
                BalanceEvent.fromEpochMicros(1_700_000_000_000_001L),
                new AccountId(account),
                new OwnerId(owner),
                Instant.now(),
                "ENABLED",
                Money.of(new BigDecimal("100.25"), "BRL"),
                Instant.now()
        );
        snapshotPort.upsertIfNewer(event);

        mockMvc.perform(get("/balances/{id}", account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account.toString()))
                .andExpect(jsonPath("$.ownerId").value(owner.toString()))
                .andExpect(jsonPath("$.amount").value("100.25"))
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void unknownAccountReturns404() throws Exception {
        mockMvc.perform(get("/balances/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void malformedAccountReturns400() throws Exception {
        mockMvc.perform(get("/balances/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
