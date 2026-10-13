package com.assessment.transaction_processor.service;

import java.time.Duration;
import java.time.Instant;

public final class RetryPolicy {

    private final Duration delay;
    private final int maxAttempts;

    public RetryPolicy(Duration delay, int maxAttempts) {
        if (delay == null || delay.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("Retry delay and max attempts must be valid");
        }
        this.delay = delay;
        this.maxAttempts = maxAttempts;
    }

    public boolean isTransient(Throwable failure) {
        return failure instanceof TransientProcessingException || failure instanceof RuntimeException;
    }

    public Instant nextAttemptAt(Instant now) {
        return now.plus(delay);
    }

    public boolean isExhausted(int attemptCount) {
        return attemptCount >= maxAttempts;
    }
}
