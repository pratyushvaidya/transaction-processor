package com.assessment.transaction_processor.dto;

public record RawTransactionRequest(
        String transactionId,
        String requestId,
        String accountId,
        String transactionType,
        String amount,
        String currency,
        String sequenceNumber) {

    public static RawTransactionRequest empty() {
        return new RawTransactionRequest(null, null, null, null, null, null, null);
    }
}
