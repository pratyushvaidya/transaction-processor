package com.assessment.transaction_processor.api;

public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException() {
        super("Request body is invalid");
    }
}
