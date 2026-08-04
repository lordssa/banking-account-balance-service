package com.itau.account.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.TimeZone;

/**
 * Shared Postgres wiring for Spring ITs: prefer Testcontainers, else local account-pg on :5432.
 */
public final class PostgresITSupport {

    static {
        // Keep LocalDateTime TIMESTAMP round-trips stable regardless of host TZ (e.g. America/Sao_Paulo).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final PostgreSQLContainer<?> CONTAINER = TestFixtures.postgres();
    private static final boolean USE_TESTCONTAINERS = startIfPossible();

    private PostgresITSupport() {
    }

    private static boolean startIfPossible() {
        try {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                return false;
            }
            CONTAINER.start();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void registerDatasource(DynamicPropertyRegistry registry) {
        if (USE_TESTCONTAINERS) {
            registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
            registry.add("spring.datasource.username", CONTAINER::getUsername);
            registry.add("spring.datasource.password", CONTAINER::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/account");
            registry.add("spring.datasource.username", () -> "account");
            registry.add("spring.datasource.password", () -> "account");
        }
        registry.add("account.sqs.enabled", () -> "false");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }
}
