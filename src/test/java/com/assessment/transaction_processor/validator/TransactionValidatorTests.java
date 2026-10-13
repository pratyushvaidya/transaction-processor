package com.assessment.transaction_processor.validator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import com.assessment.transaction_processor.dto.RawTransactionRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionValidatorTests {

    private final TransactionValidator validator = new TransactionValidator();

    @ParameterizedTest
    @MethodSource("validationCases")
    void given_raw_request_when_validated_then_returns_safe_error_codes(
            RawTransactionRequest request, java.util.List<String> expectedErrors) {
        assertThat(validator.validate(request).errorCodes()).containsExactlyElementsOf(expectedErrors);
    }

    private static Stream<Arguments> validationCases() {
        return Stream.of(
                Arguments.of(validRequest(), java.util.List.of()),
                Arguments.of(
                        new RawTransactionRequest("", null, "bad id", "credit", "0", "usd", "0"),
                        java.util.List.of(
                                "TRANSACTION_ID_REQUIRED",
                                "REQUEST_ID_REQUIRED",
                                "ACCOUNT_ID_INVALID",
                                "TRANSACTION_TYPE_INVALID",
                                "AMOUNT_INVALID",
                                "CURRENCY_INVALID",
                                "SEQUENCE_NUMBER_INVALID")),
                Arguments.of(
                        new RawTransactionRequest("t", "r", "A", "DEBIT", "1.001", "USD", "9223372036854775808"),
                        java.util.List.of("AMOUNT_INVALID", "SEQUENCE_NUMBER_INVALID")));
    }

    private static RawTransactionRequest validRequest() {
        return new RawTransactionRequest("txn-1", "req-1", "account-1", "CREDIT", "10.25", "USD", "1");
    }
}
