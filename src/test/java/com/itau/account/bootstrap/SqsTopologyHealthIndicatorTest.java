package com.itau.account.bootstrap;

import com.itau.account.support.SqsTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqsTopologyHealthIndicatorTest {

    @Test
    void observeModeStaysUpWhenInvalid() {
        SqsTopologyValidator validator = mock(SqsTopologyValidator.class);
        when(validator.cachedValidation()).thenReturn(new SqsTopologyValidator.ValidationResult(false, "dlq-arn-mismatch"));
        @SuppressWarnings("unchecked")
        ObjectProvider<SqsTopologyValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validator);

        SqsProperties props = SqsTestProperties.of(
                true, "http://q", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:dlq", "observe");

        var health = new SqsTopologyHealthIndicator(provider, props).health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("observedInvalid")).isEqualTo(true);
    }

    @Test
    void enforceModeGoesDownWhenInvalid() {
        SqsTopologyValidator validator = mock(SqsTopologyValidator.class);
        when(validator.cachedValidation()).thenReturn(new SqsTopologyValidator.ValidationResult(false, "dlq-arn-mismatch"));
        @SuppressWarnings("unchecked")
        ObjectProvider<SqsTopologyValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validator);

        SqsProperties props = SqsTestProperties.of(
                true, "http://q", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:dlq", "enforce");

        var health = new SqsTopologyHealthIndicator(provider, props).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void ingestionDisabledIsUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SqsTopologyValidator> provider = mock(ObjectProvider.class);
        SqsProperties props = SqsTestProperties.of(
                false, "http://q", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:dlq", "enforce");

        var health = new SqsTopologyHealthIndicator(provider, props).health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("mode")).isEqualTo("ingestion-disabled");
    }

    @Test
    void missingValidatorIsUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SqsTopologyValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        SqsProperties props = SqsTestProperties.of(
                true, "http://q", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:dlq", "enforce");

        var health = new SqsTopologyHealthIndicator(provider, props).health();
        assertThat(health.getDetails().get("mode")).isEqualTo("validator-unavailable");
    }

    @Test
    void offModeIsUpWithoutValidating() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SqsTopologyValidator> provider = mock(ObjectProvider.class);
        SqsTopologyValidator validator = mock(SqsTopologyValidator.class);
        when(provider.getIfAvailable()).thenReturn(validator);
        SqsProperties props = SqsTestProperties.of(
                true, "http://q", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:dlq", "off");

        var health = new SqsTopologyHealthIndicator(provider, props).health();
        assertThat(health.getDetails().get("mode")).isEqualTo("off");
    }

    @Test
    void validTopologyIsUp() {
        SqsTopologyValidator validator = mock(SqsTopologyValidator.class);
        when(validator.cachedValidation()).thenReturn(new SqsTopologyValidator.ValidationResult(true, "ok"));
        @SuppressWarnings("unchecked")
        ObjectProvider<SqsTopologyValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validator);
        SqsProperties props = SqsTestProperties.of(
                true, "http://q", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:dlq", "enforce");

        var health = new SqsTopologyHealthIndicator(provider, props).health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("reason")).isEqualTo("ok");
    }
}
