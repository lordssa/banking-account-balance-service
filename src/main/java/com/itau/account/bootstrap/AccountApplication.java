package com.itau.account.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.itau.account")
@EntityScan(basePackages = "com.itau.account.adapter.out.persistence.entity")
@EnableJpaRepositories(basePackages = "com.itau.account.adapter.out.persistence.jpa")
public class AccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}
