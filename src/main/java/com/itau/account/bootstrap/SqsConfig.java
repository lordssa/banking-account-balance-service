package com.itau.account.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(SqsProperties.class)
public class SqsConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "account.sqs", name = "enabled", havingValue = "true")
    SqsClient sqsClient(SqsProperties properties) {
        int maxConnections = Math.max(10, properties.httpMaxConnections());
        // Socket/API timeouts must exceed long-poll waitTimeSeconds.
        Duration responseTimeout = Duration.ofSeconds(Math.max(35, properties.waitTimeSeconds() + 25));
        var httpClient = ApacheHttpClient.builder()
                .maxConnections(maxConnections)
                .connectionTimeout(Duration.ofSeconds(3))
                .socketTimeout(responseTimeout)
                .build();

        var builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .httpClient(httpClient)
                .overrideConfiguration(c -> c
                        .apiCallAttemptTimeout(responseTimeout)
                        .apiCallTimeout(responseTimeout.plusSeconds(5)));

        if (properties.endpointOverride() != null && !properties.endpointOverride().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpointOverride()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
