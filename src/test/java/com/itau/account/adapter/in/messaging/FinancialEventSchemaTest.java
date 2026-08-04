package com.itau.account.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight contract guard for financial-event.schema.json required structure.
 * Full JSON-Schema engine coverage can be added when a schema validator dependency is approved.
 */
class FinancialEventSchemaTest {

    @Test
    void schemaDeclaresRequiredTransactionAndAccountBlocks() throws Exception {
        Path schemaPath = Path.of("specs/001-account-balance-query/contracts/financial-event.schema.json");
        JsonNode schema = JsonMapper.builder().build().readTree(Files.readString(schemaPath));
        assertThat(schema.get("required").toString()).contains("transaction", "account");
        assertThat(schema.at("/properties/transaction/required").toString())
                .contains("id", "type", "amount", "currency", "status", "timestamp");
        assertThat(schema.at("/properties/account/required").toString())
                .contains("id", "owner", "created_at", "status", "balance");
    }
}
