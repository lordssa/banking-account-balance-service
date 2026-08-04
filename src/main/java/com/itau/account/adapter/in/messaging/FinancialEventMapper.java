package com.itau.account.adapter.in.messaging;

import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.Money;
import com.itau.account.domain.OwnerId;
import com.itau.account.domain.TransactionId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

@Component
public class FinancialEventMapper {

    private final JsonMapper jsonMapper;

    public FinancialEventMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public BalanceEvent parse(String body, Instant receivedAt) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode tx = required(root, "transaction");
            JsonNode account = required(root, "account");
            JsonNode balance = required(account, "balance");

            BigDecimal txAmount = decimal(tx.get("amount"), "transaction.amount");
            BigDecimal balAmount = decimal(balance.get("amount"), "account.balance.amount");
            String txCurrency = text(tx, "currency");
            String balCurrency = text(balance, "currency");

            long createdAtEpoch = account.get("created_at").asLong();
            Instant accountCreatedAt = Instant.ofEpochSecond(createdAtEpoch);

            return new BalanceEvent(
                    TransactionId.parse(text(tx, "id")),
                    text(tx, "type"),
                    Money.of(txAmount, txCurrency),
                    text(tx, "status"),
                    BalanceEvent.fromEpochMicros(tx.get("timestamp").asLong()),
                    AccountId.parse(text(account, "id")),
                    OwnerId.parse(text(account, "owner")),
                    accountCreatedAt,
                    text(account, "status"),
                    Money.of(balAmount, balCurrency),
                    receivedAt
            );
        } catch (InvalidFinancialEventException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new InvalidFinancialEventException("Payload do evento financeiro inválido", ex);
        } catch (Exception ex) {
            throw new InvalidFinancialEventException("Payload do evento financeiro inválido", ex);
        }
    }

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw new InvalidFinancialEventException("Campo obrigatório ausente: " + field);
        }
        return node;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = required(parent, field);
        String value = node.asString();
        if (value == null || value.isBlank()) {
            throw new InvalidFinancialEventException("Campo obrigatório ausente: " + field);
        }
        return value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            throw new InvalidFinancialEventException("Campo obrigatório ausente: " + field);
        }
        if (node.isString()) {
            return new BigDecimal(node.asString());
        }
        if (node.isNumber()) {
            return new BigDecimal(node.asString());
        }
        throw new InvalidFinancialEventException("Campo decimal inválido: " + field);
    }
}
