package com.itau.account.adapter.in.http;

import com.itau.account.bootstrap.AccountApplication;
import com.itau.account.support.PostgresITSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AccountApplication.class)
@AutoConfigureMockMvc
class JournalAccessControlTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void journalReadIsDeniedAndAudited() throws Exception {
        mockMvc.perform(get("/internal/journal/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("JOURNAL_ACCESS_DENIED"));

        mockMvc.perform(get("/internal/journal/ingest-span")
                        .param("since", "2026-08-05T14:00:00Z")
                        .param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("JOURNAL_ACCESS_DENIED"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM administrative_journal_action WHERE action_type = ? AND result = ?",
                Integer.class,
                "JOURNAL_READ_BY_TRANSACTION",
                "DENIED"
        );
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void journalReplayIsDeniedAndAudited() throws Exception {
        mockMvc.perform(post("/internal/journal/replay"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("JOURNAL_ACCESS_DENIED"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM administrative_journal_action WHERE action_type = ? AND result = ?",
                Integer.class,
                "JOURNAL_REPLAY",
                "DENIED"
        );
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void spoofedRoleHeaderDoesNotGrantAccess() throws Exception {
        mockMvc.perform(get("/internal/journal/accounts/{id}", UUID.randomUUID())
                        .header("X-Journal-Role", "JOURNAL_READER"))
                .andExpect(status().isForbidden());
    }
}
