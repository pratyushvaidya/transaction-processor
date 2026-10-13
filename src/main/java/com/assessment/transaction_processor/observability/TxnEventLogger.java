package com.assessment.transaction_processor.observability;

import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.entity.TransactionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TxnEventLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(TxnEventLogger.class);
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    public void log(String event, String transactionId, String requestId, String accountId, String status, int attempt) {
        String maskedAccount = maskAccount(accountId);
        put("transactionId", sanitize(transactionId));
        put("requestId", sanitize(requestId));
        put("accountMasked", maskedAccount);
        put("status", sanitize(status));
        put("attempt", Integer.toString(attempt));
        try {
            LOGGER.info(
                    "{} transactionId={} requestId={} accountMasked={} status={} attempt={}",
                    event,
                    MDC.get("transactionId"),
                    MDC.get("requestId"),
                    MDC.get("accountMasked"),
                    MDC.get("status"),
                    MDC.get("attempt"));
        } finally {
            MDC.remove("transactionId");
            MDC.remove("requestId");
            MDC.remove("accountMasked");
            MDC.remove("status");
            MDC.remove("attempt");
        }
    }

    public void received(RawTransactionRequest request, String event, String status) {
        log(
                "TXN_RECEIVED",
                request.transactionId(),
                request.requestId(),
                request.accountId(),
                status,
                0);
        log(event, request.transactionId(), request.requestId(), request.accountId(), status, 0);
    }

    public void log(String event, TransactionRecord transaction) {
        log(
                event,
                transaction.getTransactionId(),
                transaction.getRequestId(),
                transaction.getAccountId(),
                transaction.getStatus().name(),
                transaction.getAttemptCount());
    }

    public void batchSummary(int count, String status) {
        log("BATCH_SUMMARY", "batch-" + count, null, null, status, 0);
    }

    public static String maskAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "unknown";
        }
        String safeAccountId = accountId.replaceAll("[\\r\\n=\\s]", "_");
        int visibleCharacters = Math.min(3, safeAccountId.length());
        return "***" + safeAccountId.substring(safeAccountId.length() - visibleCharacters);
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "-";
        }
        String safe = value.replaceAll("[\\r\\n=\\s]", "_");
        return safe.length() <= MAX_IDENTIFIER_LENGTH ? safe : safe.substring(0, MAX_IDENTIFIER_LENGTH);
    }
}
