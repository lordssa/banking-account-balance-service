package com.itau.account.bootstrap;

import com.itau.account.support.SqsTestProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.assertj.core.api.Assertions.assertThat;

class SqsConfigTest {

    @Test
    void buildsClientWithEndpointOverride() {
        SqsProperties props = SqsTestProperties.of(
                false, "", "http://127.0.0.1:4566", "sa-east-1", 16, 10, 60, 5, "", "off");

        try (SqsClient client = new SqsConfig().sqsClient(props)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void buildsClientWithoutEndpointOverride() {
        SqsProperties props = SqsTestProperties.of(
                false, "", " ", "sa-east-1", 16, 10, 60, 5, "", "off");

        try (SqsClient client = new SqsConfig().sqsClient(props)) {
            assertThat(client).isNotNull();
        }
    }
}
