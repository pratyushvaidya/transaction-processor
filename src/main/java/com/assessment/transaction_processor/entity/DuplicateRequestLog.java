package com.assessment.transaction_processor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "duplicate_request_log")
public class DuplicateRequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, length = 1024)
    private String transactionId;

    @Column(name = "request_id", length = 1024)
    private String requestId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "payload_matches_original", nullable = false)
    private boolean payloadMatchesOriginal;

    protected DuplicateRequestLog() {
    }

    public DuplicateRequestLog(
            String transactionId, String requestId, Instant receivedAt, boolean payloadMatchesOriginal) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.receivedAt = receivedAt;
        this.payloadMatchesOriginal = payloadMatchesOriginal;
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

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public boolean isPayloadMatchesOriginal() {
        return payloadMatchesOriginal;
    }
}
