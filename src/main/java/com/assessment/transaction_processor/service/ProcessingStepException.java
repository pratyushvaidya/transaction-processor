package com.assessment.transaction_processor.service;

public class ProcessingStepException extends RuntimeException {

    private final String transactionId;

    public ProcessingStepException(String transactionId, RuntimeException cause) {
        super("Transaction processing failed", cause);
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }
}
