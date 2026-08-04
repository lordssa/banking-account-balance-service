package com.itau.account.adapter.in.http;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.application.port.in.GetBalanceQuery;
import com.itau.account.domain.AccountId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@RestController
public class BalanceController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final GetBalanceQuery getBalanceQuery;
    private final IngestionMetrics metrics;

    public BalanceController(GetBalanceQuery getBalanceQuery, IngestionMetrics metrics) {
        this.getBalanceQuery = getBalanceQuery;
        this.metrics = metrics;
    }

    @GetMapping("/balances/{accountId}")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        AccountId id = AccountId.parse(accountId);
        var view = getBalanceQuery.getBalance(id);
        recordReturnedAge(view.lastUpdatedAt());
        var body = new BalanceResponse(
                view.accountId().toString(),
                view.ownerId().toString(),
                view.balance().amountPlainString(),
                view.balance().currency().value(),
                view.lastUpdatedAt().atOffset(ZoneOffset.UTC).format(ISO)
        );
        return ResponseEntity.ok(body);
    }

    private void recordReturnedAge(LocalDateTime lastUpdatedAt) {
        Duration age = Duration.between(lastUpdatedAt.atZone(ZoneOffset.UTC).toInstant(), java.time.Instant.now());
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        metrics.recordReturnedBalanceAge(age);
    }

    public record BalanceResponse(
            String accountId,
            String ownerId,
            String amount,
            String currency,
            String lastUpdatedAt
    ) {
    }
}
