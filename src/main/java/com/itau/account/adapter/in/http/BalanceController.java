package com.itau.account.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.application.port.in.GetBalanceQuery;
import com.itau.account.domain.AccountId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@RestController
public class BalanceController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("America/Sao_Paulo");

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
                new BalanceResponse.Balance(
                        new BigDecimal(view.balance().amountPlainString()),
                        view.balance().currency().value()),
                view.lastUpdatedAt()
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(DISPLAY_ZONE)
                        .format(ISO));
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
            String id,
            String owner,
            Balance balance,
            @JsonProperty("updated_at") String updatedAt
    ) {
        public record Balance(BigDecimal amount, String currency) {
        }
    }
}
