package com.assessment.transaction_processor.validator;

import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.dto.ValidationResult;
import com.assessment.transaction_processor.enums.TransactionType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TransactionValidator {

    private static final Pattern ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9-]{1,32}$");
    private static final Pattern AMOUNT = Pattern.compile("^\\d+(?:\\.\\d{1,2})?$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public ValidationResult validate(RawTransactionRequest request) {
        List<String> errors = new ArrayList<>();
        if (isBlank(request.transactionId())) {
            errors.add("TRANSACTION_ID_REQUIRED");
        }
        if (isBlank(request.requestId())) {
            errors.add("REQUEST_ID_REQUIRED");
        }
        if (isBlank(request.accountId())) {
            errors.add("ACCOUNT_ID_REQUIRED");
        } else if (!ACCOUNT_ID.matcher(request.accountId()).matches()) {
            errors.add("ACCOUNT_ID_INVALID");
        }
        if (parseType(request.transactionType()) == null) {
            errors.add("TRANSACTION_TYPE_INVALID");
        }
        if (!isPositiveAmount(request.amount())) {
            errors.add("AMOUNT_INVALID");
        }
        if (request.currency() == null || !CURRENCY.matcher(request.currency()).matches()) {
            errors.add("CURRENCY_INVALID");
        }
        if (!isPositiveLong(request.sequenceNumber())) {
            errors.add("SEQUENCE_NUMBER_INVALID");
        }
        return new ValidationResult(errors);
    }

    private static boolean isPositiveAmount(String rawAmount) {
        if (rawAmount == null || !AMOUNT.matcher(rawAmount).matches()) {
            return false;
        }
        BigDecimal amount = new BigDecimal(rawAmount);
        int storedPrecision = amount.precision() + Math.max(0, 2 - amount.scale());
        return amount.signum() > 0 && storedPrecision <= 19;
    }

    private static boolean isPositiveLong(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static TransactionType parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TransactionType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
