package com.itau.account.adapter.in.http;

import com.itau.account.bootstrap.AccountApplication;
import com.itau.account.support.PostgresITSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AccountApplication.class,
        properties = "account.journal.allow-anonymous-read=true"
)
@AutoConfigureMockMvc
class JournalIngestSpanAccessIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Autowired MockMvc mockMvc;

    @Test
    void ingestSpanIsReadableWhenAnonymousReadEnabled() throws Exception {
        mockMvc.perform(get("/internal/journal/ingest-span")
                        .param("since", "2026-08-05T14:00:00Z")
                        .param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(0));
    }
}
