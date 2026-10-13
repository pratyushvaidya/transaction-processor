package com.assessment.transaction_processor.enums;

public enum FailureReason {
    VALIDATION_FAILED,
    INSUFFICIENT_FUNDS,
    SEQUENCE_TIMEOUT,
    RETRIES_EXHAUSTED,
    STALE_SEQUENCE,
    SEQUENCE_CONFLICT,
    MALFORMED
}
