package com.assessment.transaction_processor.enums;

public enum IngestionOutcome {
    PENDING,
    FAILED,
    DUPLICATE,
    REJECTED_MALFORMED,
    REJECTED_STALE_SEQUENCE,
    REJECTED_SEQUENCE_CONFLICT
}
