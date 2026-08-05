package com.itau.account.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialEventMapperTest {

    private final FinancialEventMapper mapper = new FinancialEventMapper(JsonMapper.builder().build());

    @Test
    void parsesValidPayload() {
        String tx = UUID.randomUUID().toString();
        String account = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        String body = """
                {
                  "transaction": {
                    "id": "%s",
                    "type": "CREDIT",
                    "amount": "10.50",
                    "currency": "BRL",
                    "status": "APPROVED",
                    "timestamp": 1700000000000001
                  },
                  "account": {
                    "id": "%s",
                    "owner": "%s",
                    "created_at": 1609459200,
                    "status": "ENABLED",
                    "balance": { "amount": 100.25, "currency": "BRL" }
                  }
                }
                """.formatted(tx, account, owner);

        var event = mapper.parse(body, Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(event.transactionId().toString()).isEqualTo(tx);
        assertThat(event.authoritativeBalance().amountPlainString()).isEqualTo("100.25");
    }

    @Test
    void rejectsMissingTransaction() {
        assertThatThrownBy(() -> mapper.parse("{\"account\":{}}", Instant.now()))
                .isInstanceOf(InvalidFinancialEventException.class);
    }

    @Test
    void rejectsBlankOwner() {
        String body = """
                {
                  "transaction": {
                    "id": "%s",
                    "type": "CREDIT",
                    "amount": "10.50",
                    "currency": "BRL",
                    "status": "APPROVED",
                    "timestamp": 1700000000000001
                  },
                  "account": {
                    "id": "%s",
                    "owner": " ",
                    "created_at": 1609459200,
                    "status": "ENABLED",
                    "balance": { "amount": 100.25, "currency": "BRL" }
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> mapper.parse(body, Instant.now()))
                .isInstanceOf(InvalidFinancialEventException.class);
    }

    @Test
    void rejectsNonDecimalAmount() {
        String body = """
                {
                  "transaction": {
                    "id": "%s",
                    "type": "CREDIT",
                    "amount": true,
                    "currency": "BRL",
                    "status": "APPROVED",
                    "timestamp": 1700000000000001
                  },
                  "account": {
                    "id": "%s",
                    "owner": "%s",
                    "created_at": 1609459200,
                    "status": "ENABLED",
                    "balance": { "amount": 100.25, "currency": "BRL" }
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> mapper.parse(body, Instant.now()))
                .isInstanceOf(InvalidFinancialEventException.class);
    }

    @Test
    void rejectsMalformedAccountUuidWithEightHexSecondGroup() {
        // Perf harness previously emitted 00000000-<8hex>-4000-8000-... which is not 8-4-4-4-12.
        String body = """
                {"transaction":{"id":"bbbbbbbb-cccc-4ddd-8eee-000000700008","type":"CREDIT","amount":"10.00","currency":"BRL","status":"APPROVED","timestamp":1785889858000001},"account":{"id":"00000000-43a6fc1b-4000-8000-000000000032","owner":"00000000-43a6fc1b-4000-9000-000000000032","created_at":1609459200,"status":"ENABLED","balance":{"amount":1.00,"currency":"BRL"}}}
                """;
        assertThatThrownBy(() -> mapper.parse(body, Instant.now()))
                .isInstanceOf(InvalidFinancialEventException.class)
                .hasMessageContaining("Payload do evento financeiro inválido");
    }

    @Test
    void parsesPerfStyleAccountUuid() {
        String body = """
                {"transaction":{"id":"bbbbbbbb-cccc-4ddd-8eee-000000700008","type":"CREDIT","amount":"10.00","currency":"BRL","status":"APPROVED","timestamp":1785889858000001},"account":{"id":"00000000-43a6-fc1b-8000-000000000032","owner":"00000000-43a6-fc1b-9000-000000000032","created_at":1609459200,"status":"ENABLED","balance":{"amount":1.00,"currency":"BRL"}}}
                """;
        var event = mapper.parse(body, Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(event.accountId().toString()).isEqualTo("00000000-43a6-fc1b-8000-000000000032");
        assertThat(event.authoritativeBalance().amountPlainString()).isEqualTo("1");
    }
}
