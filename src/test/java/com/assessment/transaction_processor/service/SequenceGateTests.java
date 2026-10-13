package com.assessment.transaction_processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SequenceGateTests {

    private final SequenceGate gate = new SequenceGate();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void given_no_transaction_at_cursor_when_decided_then_waits_for_gap() {
        assertEquals(
                SequenceGate.Decision.WAIT_GAP,
                gate.decide(1L, Optional.empty(), now));
    }

    @Test
    void given_awaiting_sequence_at_cursor_when_decided_then_processes() {
        assertEquals(
                SequenceGate.Decision.PROCESS,
                gate.decide(1L, Optional.of(transaction(
                        TransactionStatus.PENDING, PendingReason.AWAITING_SEQUENCE, null, null)), now));
    }

    @Test
    void given_retry_due_at_cursor_when_decided_then_processes() {
        assertEquals(
                SequenceGate.Decision.PROCESS,
                gate.decide(1L, Optional.of(transaction(
                        TransactionStatus.PENDING,
                        PendingReason.AWAITING_RETRY,
                        now.minusSeconds(1),
                        null)), now));
    }

    @Test
    void given_retry_in_future_at_cursor_when_decided_then_waits_for_retry() {
        assertEquals(
                SequenceGate.Decision.WAIT_RETRY,
                gate.decide(1L, Optional.of(transaction(
                        TransactionStatus.PENDING,
                        PendingReason.AWAITING_RETRY,
                        now.plusSeconds(1),
                        null)), now));
    }

    @Test
    void given_failed_at_cursor_when_decided_then_skips_terminal() {
        assertEquals(
                SequenceGate.Decision.SKIP_TERMINAL,
                gate.decide(1L, Optional.of(transaction(
                        TransactionStatus.FAILED, null, null, FailureReason.VALIDATION_FAILED)), now));
    }

    @Test
    void given_processed_at_cursor_when_decided_then_skips_terminal() {
        assertEquals(
                SequenceGate.Decision.SKIP_TERMINAL,
                gate.decide(1L, Optional.of(transaction(
                        TransactionStatus.PROCESSED, null, null, null)), now));
    }

    private static TransactionRecord transaction(
            TransactionStatus status,
            PendingReason pendingReason,
            Instant nextAttemptAt,
            FailureReason failureReason) {
        TransactionRecord transaction = new TransactionRecord(
                "txn-1",
                "req-1",
                "ACC-201",
                1L,
                TransactionType.CREDIT,
                new BigDecimal("10.00"),
                "USD",
                status,
                pendingReason,
                failureReason,
                Instant.EPOCH,
                Instant.EPOCH);
        if (nextAttemptAt != null) {
            transaction.setNextAttemptAt(nextAttemptAt);
        }
        return transaction;
    }
}
