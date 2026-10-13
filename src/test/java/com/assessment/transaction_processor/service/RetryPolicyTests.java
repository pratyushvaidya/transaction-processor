package com.assessment.transaction_processor.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryPolicyTests {

    private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(30), 3);

    @Test
    void given_transient_processing_exception_when_classified_then_is_transient() {
        assertThat(policy.isTransient(new TransientProcessingException("temporary"))).isTrue();
    }

    @Test
    void given_unexpected_runtime_exception_when_classified_then_is_transient() {
        assertThat(policy.isTransient(new IllegalStateException("unexpected"))).isTrue();
    }

    @Test
    void given_checked_exception_when_classified_then_is_not_transient() {
        assertThat(policy.isTransient(new Exception("checked"))).isFalse();
    }

    @Test
    void given_current_time_when_next_attempt_requested_then_adds_configured_delay() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(policy.nextAttemptAt(now)).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void given_attempt_count_when_checked_for_exhaustion_then_uses_total_execution_limit() {
        assertThat(policy.isExhausted(2)).isFalse();
        assertThat(policy.isExhausted(3)).isTrue();
    }
}
