package com.itau.account.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqsPropertiesTest {

    @Test
    void constructorExposesBoundValues() {
        SqsProperties props = new SqsProperties(
                true,
                "http://q",
                "http://localstack:4566",
                "us-east-1",
                8,
                5,
                30,
                7
        );

        assertThat(props.enabled()).isTrue();
        assertThat(props.queueUrl()).isEqualTo("http://q");
        assertThat(props.endpointOverride()).isEqualTo("http://localstack:4566");
        assertThat(props.region()).isEqualTo("us-east-1");
        assertThat(props.maxConcurrent()).isEqualTo(8);
        assertThat(props.waitTimeSeconds()).isEqualTo(5);
        assertThat(props.visibilityTimeoutSeconds()).isEqualTo(30);
        assertThat(props.maxReceiveCount()).isEqualTo(7);
    }
}
