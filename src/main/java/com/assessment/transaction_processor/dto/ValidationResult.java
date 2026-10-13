package com.assessment.transaction_processor.dto;

import java.util.List;

public record ValidationResult(List<String> errorCodes) {

    public ValidationResult {
        errorCodes = List.copyOf(errorCodes);
    }

    public boolean isValid() {
        return errorCodes.isEmpty();
    }
}
