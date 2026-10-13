package com.assessment.transaction_processor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.enums.TransactionType;

@Entity
@Table(
        name = "transactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_transactions_account_sequence",
                columnNames = {"account_id", "sequence_number"}),
        indexes = @Index(
                name = "idx_transactions_status_pending_reason",
                columnList = "status, pending_reason"))
public class TransactionRecord {

    @Id
    @Column(name = "transaction_id", nullable = false, length = 1024)
    private String transactionId;

    @Column(name = "request_id", length = 1024)
    private String requestId;

    @Column(name = "account_id", nullable = false, length = 1024)
    private String accountId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 16)
    private TransactionType type;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 1024)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_reason", length = 32)
    private PendingReason pendingReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 32)
    private FailureReason failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error_message", length = 128)
    private String lastErrorMessage;

    protected TransactionRecord() {
    }

    public TransactionRecord(
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
            Instant receivedAt,
            Instant updatedAt) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.accountId = accountId;
        this.sequenceNumber = sequenceNumber;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.pendingReason = pendingReason;
        this.failureReason = failureReason;
        this.attemptCount = 0;
        this.receivedAt = receivedAt;
        this.updatedAt = updatedAt;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getAccountId() {
        return accountId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public PendingReason getPendingReason() {
        return pendingReason;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void markProcessing(Instant at) {
        status = TransactionStatus.PROCESSING;
        pendingReason = null;
        updatedAt = at;
    }

    public void markFailed(FailureReason reason, Instant at) {
        status = TransactionStatus.FAILED;
        pendingReason = null;
        failureReason = reason;
        nextAttemptAt = null;
        lastErrorMessage = null;
        updatedAt = at;
    }

    public void markProcessed(Instant at) {
        status = TransactionStatus.PROCESSED;
        pendingReason = null;
        failureReason = null;
        lastErrorMessage = null;
        nextAttemptAt = null;
        processedAt = at;
        updatedAt = at;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public void incrementAttemptCount() {
        attemptCount = Math.addExact(attemptCount, 1);
    }

    public void markAwaitingRetry(Instant retryAt, Instant at, String safeMessage) {
        status = TransactionStatus.PENDING;
        pendingReason = PendingReason.AWAITING_RETRY;
        failureReason = null;
        nextAttemptAt = retryAt;
        lastErrorMessage = safeMessage;
        updatedAt = at;
    }

    public void markReclaimedForRetry(Instant at) {
        incrementAttemptCount();
        markAwaitingRetry(at, at, null);
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }
}
