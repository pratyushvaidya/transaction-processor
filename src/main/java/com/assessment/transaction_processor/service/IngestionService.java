package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.dto.ValidationResult;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.DuplicateRequestLog;
import com.assessment.transaction_processor.entity.RejectedRecord;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.IngestionOutcome;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.enums.TransactionType;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import com.assessment.transaction_processor.validator.TransactionValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionService {

    private static final String ACCOUNT_ID_PATTERN = "^[A-Za-z0-9-]{1,32}$";
    private static final String MALFORMED_MESSAGE = "Required transaction identifiers are missing or invalid";
    private static final String STALE_MESSAGE = "Sequence number is older than the account cursor";
    private static final String CONFLICT_MESSAGE = "Sequence slot is already owned";

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository transactionRepository;
    private final DuplicateRequestLogRepository duplicateLogRepository;
    private final RejectedRecordRepository rejectedRecordRepository;
    private final TransactionValidator validator;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public IngestionService(
            AccountRepository accountRepository,
            TransactionRecordRepository transactionRepository,
            DuplicateRequestLogRepository duplicateLogRepository,
            RejectedRecordRepository rejectedRecordRepository,
            TransactionValidator validator,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.duplicateLogRepository = duplicateLogRepository;
        this.rejectedRecordRepository = rejectedRecordRepository;
        this.validator = validator;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public IngestionOutcome ingest(RawTransactionRequest request) {
        try {
            return transactionTemplate.execute(status -> ingestInTransaction(request));
        } catch (DataIntegrityViolationException exception) {
            return recordDuplicateAfterRace(request);
        }
    }

    public List<IngestionOutcome> ingestBatch(List<RawTransactionRequest> requests) {
        List<IngestionOutcome> outcomes = new ArrayList<>(requests.size());
        for (RawTransactionRequest request : requests) {
            outcomes.add(ingest(request));
        }
        return List.copyOf(outcomes);
    }

    private IngestionOutcome ingestInTransaction(RawTransactionRequest request) {
        String transactionId = nonBlank(request.transactionId());
        String accountId = nonBlank(request.accountId());
        Long sequenceNumber = parseLong(request.sequenceNumber()).orElse(null);
        if (transactionId == null || accountId == null || sequenceNumber == null) {
            rejectedRecordRepository.save(new RejectedRecord(
                    transactionId,
                    nonBlank(request.requestId()),
                    accountId,
                    sequenceNumber,
                    FailureReason.MALFORMED,
                    MALFORMED_MESSAGE,
                    clock.instant()));
            return IngestionOutcome.REJECTED_MALFORMED;
        }

        Optional<TransactionRecord> original = transactionRepository.findById(transactionId);
        if (original.isPresent()) {
            saveDuplicateLog(request, original.get());
            return IngestionOutcome.DUPLICATE;
        }

        Account account = null;
        if (accountId.matches(ACCOUNT_ID_PATTERN)) {
            account = accountRepository.findById(accountId).orElseGet(
                    () -> accountRepository.saveAndFlush(new Account(accountId)));
            if (sequenceNumber < account.getNextExpectedSeq()) {
                rejectedRecordRepository.save(new RejectedRecord(
                        transactionId,
                        nonBlank(request.requestId()),
                        accountId,
                        sequenceNumber,
                        FailureReason.STALE_SEQUENCE,
                        STALE_MESSAGE,
                        clock.instant()));
                return IngestionOutcome.REJECTED_STALE_SEQUENCE;
            }
        }

        if (transactionRepository.existsByAccountIdAndSequenceNumber(accountId, sequenceNumber)) {
            rejectedRecordRepository.save(new RejectedRecord(
                    transactionId,
                    nonBlank(request.requestId()),
                    accountId,
                    sequenceNumber,
                    FailureReason.SEQUENCE_CONFLICT,
                    CONFLICT_MESSAGE,
                    clock.instant()));
            return IngestionOutcome.REJECTED_SEQUENCE_CONFLICT;
        }

        ValidationResult validation = validator.validate(request);
        TransactionType type = parseType(request.transactionType()).orElse(null);
        BigDecimal amount = parseAmount(request.amount()).orElse(null);
        Instant receivedAt = clock.instant();
        boolean valid = validation.isValid();
        transactionRepository.save(new TransactionRecord(
                transactionId,
                nonBlank(request.requestId()),
                accountId,
                sequenceNumber,
                type,
                amount,
                request.currency(),
                valid ? TransactionStatus.PENDING : TransactionStatus.FAILED,
                valid ? PendingReason.AWAITING_SEQUENCE : null,
                valid ? null : FailureReason.VALIDATION_FAILED,
                receivedAt,
                receivedAt));
        return valid ? IngestionOutcome.PENDING : IngestionOutcome.FAILED;
    }

    private IngestionOutcome recordDuplicateAfterRace(RawTransactionRequest request) {
        String transactionId = nonBlank(request.transactionId());
        if (transactionId == null) {
            throw new DataIntegrityViolationException("Unable to persist malformed transaction");
        }
        return transactionTemplate.execute(status -> {
            TransactionRecord original = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new DataIntegrityViolationException(
                            "Transaction constraint violation was not an idempotency collision"));
            saveDuplicateLog(request, original);
            return IngestionOutcome.DUPLICATE;
        });
    }

    private void saveDuplicateLog(RawTransactionRequest request, TransactionRecord original) {
        duplicateLogRepository.save(new DuplicateRequestLog(
                original.getTransactionId(),
                nonBlank(request.requestId()),
                clock.instant(),
                payloadMatchesOriginal(request, original)));
    }

    private static boolean payloadMatchesOriginal(
            RawTransactionRequest request, TransactionRecord original) {
        return equal(request.transactionId(), original.getTransactionId())
                && equal(request.requestId(), original.getRequestId())
                && equal(request.accountId(), original.getAccountId())
                && parseLong(request.sequenceNumber()).filter(value -> value == original.getSequenceNumber()).isPresent()
                && parseType(request.transactionType()).equals(Optional.ofNullable(original.getType()))
                && decimalEquals(request.amount(), original.getAmount())
                && equal(request.currency(), original.getCurrency());
    }

    private static Optional<TransactionType> parseType(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TransactionType.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> parseAmount(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            return amount.precision() <= 19 ? Optional.of(amount) : Optional.empty();
        } catch (NumberFormatException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Long> parseLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean decimalEquals(String value, BigDecimal original) {
        if (original == null || value == null) {
            return original == null && value == null;
        }
        try {
            return new BigDecimal(value).compareTo(original) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean equal(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
