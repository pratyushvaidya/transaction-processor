package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import java.time.Instant;
import java.util.Optional;

public final class SequenceGate {

    public Decision decide(long cursor, Optional<TransactionRecord> atCursor, Instant now) {
        if (atCursor.isEmpty()) {
            return Decision.WAIT_GAP;
        }
        TransactionRecord transaction = atCursor.get();
        if (transaction.getSequenceNumber() != cursor) {
            throw new IllegalArgumentException("Transaction does not occupy the cursor sequence");
        }
        if (transaction.getStatus() == TransactionStatus.FAILED
                || transaction.getStatus() == TransactionStatus.PROCESSED) {
            return Decision.SKIP_TERMINAL;
        }
        if (transaction.getStatus() == TransactionStatus.PROCESSING) {
            return Decision.WAIT_RETRY;
        }
        if (transaction.getStatus() != TransactionStatus.PENDING) {
            return Decision.WAIT_RETRY;
        }
        if (transaction.getPendingReason() == PendingReason.AWAITING_SEQUENCE) {
            return Decision.PROCESS;
        }
        if (transaction.getPendingReason() == PendingReason.AWAITING_RETRY) {
            return transaction.getNextAttemptAt() != null && !transaction.getNextAttemptAt().isAfter(now)
                    ? Decision.PROCESS
                    : Decision.WAIT_RETRY;
        }
        return Decision.WAIT_RETRY;
    }

    public enum Decision {
        PROCESS,
        WAIT_GAP,
        WAIT_RETRY,
        SKIP_TERMINAL
    }
}
