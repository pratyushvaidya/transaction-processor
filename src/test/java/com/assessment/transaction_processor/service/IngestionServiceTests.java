package com.assessment.transaction_processor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transaction_processor.TransactionProcessorApplication;
import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.DuplicateRequestLog;
import com.assessment.transaction_processor.entity.RejectedRecord;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import com.assessment.transaction_processor.enums.IngestionOutcome;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TransactionProcessorApplication.class)
class IngestionServiceTests {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRecordRepository transactionRepository;

    @Autowired
    private DuplicateRequestLogRepository duplicateLogRepository;

    @Autowired
    private RejectedRecordRepository rejectedRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        rejectedRecordRepository.deleteAll();
        duplicateLogRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void given_new_record_when_ingested_then_buffered_in_its_sequence_slot() {
        assertThat(ingestionService.ingest(request("txn-1", "req-1", "1", "CREDIT", "10.00")))
                .isEqualTo(IngestionOutcome.PENDING);

        TransactionRecord stored = transactionRepository.findById("txn-1").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(stored.getPendingReason()).isEqualTo(PendingReason.AWAITING_SEQUENCE);
        assertThat(accountRepository.findById("account-1").orElseThrow())
                .extracting(Account::getNextExpectedSeq)
                .isEqualTo(1L);
    }

    @Test
    void given_same_payload_again_when_ingested_then_logs_duplicate_without_changing_original() {
        RawTransactionRequest original = request("txn-1", "req-1", "1", "CREDIT", "10.00");
        ingestionService.ingest(original);
        TransactionRecord before = transactionRepository.findById("txn-1").orElseThrow();

        assertThat(ingestionService.ingest(original)).isEqualTo(IngestionOutcome.DUPLICATE);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(duplicateLogRepository.findAll())
                .singleElement()
                .extracting(DuplicateRequestLog::isPayloadMatchesOriginal)
                .isEqualTo(true);
        assertThat(transactionRepository.findById("txn-1").orElseThrow().getUpdatedAt())
                .isEqualTo(before.getUpdatedAt());
    }

    @Test
    void given_changed_payload_for_existing_id_when_ingested_then_original_remains_unchanged() {
        ingestionService.ingest(request("txn-1", "req-1", "1", "CREDIT", "10.00"));

        assertThat(ingestionService.ingest(request("txn-1", "req-2", "2", "DEBIT", "99.00")))
                .isEqualTo(IngestionOutcome.DUPLICATE);

        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.findById("txn-1").orElseThrow())
                .satisfies(original -> {
                    assertThat(original.getRequestId()).isEqualTo("req-1");
                    assertThat(original.getSequenceNumber()).isEqualTo(1L);
                    assertThat(original.getType().name()).isEqualTo("CREDIT");
                    assertThat(original.getAmount()).hasToString("10.00");
                });
        assertThat(duplicateLogRepository.findAll())
                .singleElement()
                .extracting(DuplicateRequestLog::isPayloadMatchesOriginal)
                .isEqualTo(false);
    }

    @Test
    void given_different_transaction_for_owned_slot_when_ingested_then_rejected_as_conflict() {
        ingestionService.ingest(request("txn-1", "req-1", "1", "CREDIT", "10.00"));

        assertThat(ingestionService.ingest(request("txn-2", "req-2", "1", "CREDIT", "20.00")))
                .isEqualTo(IngestionOutcome.REJECTED_SEQUENCE_CONFLICT);
        assertThat(rejectedRecordRepository.findAll())
                .singleElement()
                .extracting(RejectedRecord::getFailureReason)
                .isEqualTo(FailureReason.SEQUENCE_CONFLICT);
    }

    @Test
    void given_sequence_below_cursor_when_ingested_then_rejected_as_stale() {
        accountRepository.saveAndFlush(new Account("account-1"));
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> jdbcTemplate.update(
                        "UPDATE accounts SET next_expected_seq = 2 WHERE account_id = ?",
                        "account-1"));

        assertThat(ingestionService.ingest(request("txn-1", "req-1", "1", "CREDIT", "10.00")))
                .isEqualTo(IngestionOutcome.REJECTED_STALE_SEQUENCE);
        assertThat(rejectedRecordRepository.findAll())
                .singleElement()
                .extracting(RejectedRecord::getFailureReason)
                .isEqualTo(FailureReason.STALE_SEQUENCE);
    }

    @Test
    void given_invalid_business_fields_when_ingested_then_stored_as_failed_in_sequence_slot() {
        RawTransactionRequest invalid =
                new RawTransactionRequest("txn-1", "req-1", "account-1", "TRANSFER", "0.001", "USD", "1");

        assertThat(ingestionService.ingest(invalid)).isEqualTo(IngestionOutcome.FAILED);
        TransactionRecord stored = transactionRepository.findById("txn-1").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(stored.getFailureReason()).isEqualTo(FailureReason.VALIDATION_FAILED);
        assertThat(stored.getPendingReason()).isNull();
        assertThat(accountRepository.existsById("account-1")).isTrue();
    }

    @Test
    void given_missing_or_unparseable_slot_fields_when_ingested_then_rejected_as_malformed() {
        RawTransactionRequest malformed =
                new RawTransactionRequest("txn-1", "req-1", "account-1", "CREDIT", "1.00", "USD", "not-a-number");

        assertThat(ingestionService.ingest(malformed)).isEqualTo(IngestionOutcome.REJECTED_MALFORMED);
        assertThat(transactionRepository.count()).isZero();
        assertThat(rejectedRecordRepository.findAll())
                .singleElement()
                .extracting(RejectedRecord::getFailureReason)
                .isEqualTo(FailureReason.MALFORMED);
    }

    @Test
    void given_batch_with_malformed_entry_when_ingested_then_other_entries_are_still_stored() {
        List<RawTransactionRequest> batch = List.of(
                request("txn-1", "req-1", "1", "CREDIT", "10.00"),
                new RawTransactionRequest(null, "req-bad", "account-1", "CREDIT", "1.00", "USD", "2"),
                request("txn-3", "req-3", "3", "DEBIT", "2.00"));

        assertThat(ingestionService.ingestBatch(batch)).containsExactly(
                IngestionOutcome.PENDING,
                IngestionOutcome.REJECTED_MALFORMED,
                IngestionOutcome.PENDING);
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(rejectedRecordRepository.count()).isEqualTo(1);
    }

    private static RawTransactionRequest request(
            String transactionId, String requestId, String sequenceNumber, String type, String amount) {
        return new RawTransactionRequest(
                transactionId, requestId, "account-1", type, amount, "USD", sequenceNumber);
    }
}
