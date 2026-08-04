package com.itau.account;

import com.itau.account.bootstrap.AccountApplication;
import com.itau.account.support.PostgresITSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = AccountApplication.class)
class AccountApplicationTests {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(registry);
    }

    @Test
    void contextLoads() {
    }
}
