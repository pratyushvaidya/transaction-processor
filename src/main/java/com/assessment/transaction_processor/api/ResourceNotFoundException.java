package com.assessment.transaction_processor.api;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException() {
        super("Requested resource was not found");
    }
}
