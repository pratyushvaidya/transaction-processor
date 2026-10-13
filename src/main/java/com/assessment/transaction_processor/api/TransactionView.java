package com.assessment.transaction_processor.api;

import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionView(
        String transactionId,
        String requestId,
        String accountId,
        long sequenceNumber,
        TransactionType type,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        PendingReason pendingReason,
        FailureReason failureReason,
        int attemptCount,
        Instant nextAttemptAt,
        Instant receivedAt,
        Instant updatedAt,
        Instant processedAt) {

    public static TransactionView from(TransactionRecord transaction) {
        return new TransactionView(
                transaction.getTransactionId(),
                transaction.getRequestId(),
                transaction.getAccountId(),
                transaction.getSequenceNumber(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getPendingReason(),
                transaction.getFailureReason(),
                transaction.getAttemptCount(),
                transaction.getNextAttemptAt(),
                transaction.getReceivedAt(),
                transaction.getUpdatedAt(),
                transaction.getProcessedAt());
    }
}
