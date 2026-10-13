package com.assessment.transaction_processor.entity;
import com.assessment.transaction_processor.enums.FailureReason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "rejected_records")
public class RejectedRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", length = 1024)
    private String transactionId;

    @Column(name = "request_id", length = 1024)
    private String requestId;

    @Column(name = "account_id", length = 1024)
    private String accountId;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", nullable = false, length = 32)
    private FailureReason failureReason;

    @Column(name = "safe_message", nullable = false, length = 128)
    private String safeMessage;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected RejectedRecord() {
    }

    public RejectedRecord(
            String transactionId,
            String requestId,
            String accountId,
            Long sequenceNumber,
            FailureReason failureReason,
            String safeMessage,
            Instant receivedAt) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.accountId = accountId;
        this.sequenceNumber = sequenceNumber;
        this.failureReason = failureReason;
        this.safeMessage = safeMessage;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
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

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public RejectedRecordView toView() {
        return new RejectedRecordView(
                id, transactionId, requestId, accountId, sequenceNumber, failureReason, safeMessage, receivedAt);
    }

    public record RejectedRecordView(
            Long id,
            String transactionId,
            String requestId,
            String accountId,
            Long sequenceNumber,
            FailureReason failureReason,
            String safeMessage,
            Instant receivedAt) {
    }
}
