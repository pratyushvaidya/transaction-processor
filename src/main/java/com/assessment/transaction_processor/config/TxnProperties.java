package com.assessment.transaction_processor.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "txn")
public record TxnProperties(
        Retry retry,
        Pending pending,
        Processing processing,
        Scheduler scheduler,
        Source source,
        FaultInjection faultInjection) {

    public record Retry(Duration delay, int maxAttempts) {
    }

    public record Pending(Duration timeout) {
    }

    public record Processing(Duration leaseTimeout) {
    }

    public record Scheduler(Duration pollInterval, boolean enabled) {
    }

    public record Source(String file) {
    }

    public record FaultInjection(boolean enabled, Map<String, Integer> failures) {
        public FaultInjection {
            failures = Map.copyOf(failures);
        }
    }
}
