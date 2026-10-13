package com.assessment.transaction_processor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transaction_processor.TransactionProcessorApplication;
import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.entity.LedgerEntry;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.IngestionOutcome;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.LedgerEntryRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import com.assessment.transaction_processor.support.MutableClock;
import com.assessment.transaction_processor.support.MutableClockConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        classes = TransactionProcessorApplication.class,
        properties = {
            "txn.fault-injection.enabled=true",
            "txn.fault-injection.failures.TXN-ONCE=1",
            "txn.fault-injection.failures.TXN-ALWAYS=3"
        })
@Import(MutableClockConfiguration.class)
class ResilienceIntegrationTests {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ProcessingEngine processingEngine;

    @Autowired
    private ResilienceJobs resilienceJobs;

    @Autowired
    private MutableClock clock;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRecordRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private DuplicateRequestLogRepository duplicateLogRepository;

    @Autowired
    private RejectedRecordRepository rejectedRepository;

    @BeforeEach
    void resetDatabaseAndClock() {
        clock.setInstant(START);
        ledgerRepository.deleteAll();
        rejectedRepository.deleteAll();
        duplicateLogRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void given_transient_failure_once_when_retry_succeeds_then_applies_balance_once() {
        ingestionService.ingestBatch(List.of(request("TXN-ONCE", 1, "CREDIT", "10.00")));
        TransactionRecord failedAttempt = transactionRepository.findById("TXN-ONCE").orElseThrow();

        assertThat(failedAttempt.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(failedAttempt.getPendingReason()).isEqualTo(PendingReason.AWAITING_RETRY);
        assertThat(ledgerRepository.count()).isZero();
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(failedAttempt.getLastErrorMessage()).isEqualTo("TransientProcessingException");

        clock.advance(Duration.ofSeconds(30));
        resilienceJobs.runDueRetries();

        TransactionRecord completed = transactionRepository.findById("TXN-ONCE").orElseThrow();
        assertThat(completed.getAttemptCount()).isEqualTo(2);
        assertThat(completed.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(ledgerRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("10.00");
    }

    @Test
    void given_post_ledger_transient_failure_when_step_rolls_back_then_balance_and_ledger_are_unchanged() {
        ingestionService.ingestBatch(List.of(request("TXN-ONCE", 1, "CREDIT", "10.00")));

        assertThat(ledgerRepository.findAll()).isEmpty();
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(transactionRepository.findById("TXN-ONCE").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void given_retry_not_yet_due_when_job_runs_then_waits_until_delay_elapses() {
        ingestionService.ingestBatch(List.of(request("TXN-ONCE", 1, "CREDIT", "10.00")));
        clock.advance(Duration.ofSeconds(29));

        resilienceJobs.runDueRetries();

        assertThat(ledgerRepository.count()).isZero();
        assertThat(transactionRepository.findById("TXN-ONCE").orElseThrow().getAttemptCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        resilienceJobs.runDueRetries();

        assertThat(ledgerRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.findById("TXN-ONCE").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
    }

    @Test
    void given_retry_attempts_are_exhausted_when_retried_then_fails_terminally_and_next_sequence_runs() {
        ingestionService.ingestBatch(List.of(
                request("TXN-ALWAYS", 1, "CREDIT", "10.00"),
                request("TXN-NEXT", 2, "CREDIT", "5.00")));

        for (int attempt = 0; attempt < 2; attempt++) {
            clock.advance(Duration.ofSeconds(30));
            resilienceJobs.runDueRetries();
        }

        TransactionRecord exhausted = transactionRepository.findById("TXN-ALWAYS").orElseThrow();
        assertThat(exhausted.getAttemptCount()).isEqualTo(3);
        assertThat(exhausted.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(exhausted.getFailureReason()).isEqualTo(FailureReason.RETRIES_EXHAUSTED);
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getNextExpectedSeq()).isEqualTo(2L);

        processingEngine.drainAccount("ACC-201");

        assertThat(transactionRepository.findById("TXN-NEXT").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(ledgerRepository.findAll())
                .extracting(LedgerEntry::getTransactionId)
                .containsExactly("TXN-NEXT");
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("5.00");
    }

    @Test
    void given_retry_pending_and_next_sequence_when_retry_succeeds_then_next_sequence_processes_after_it() {
        ingestionService.ingestBatch(List.of(
                request("TXN-ONCE", 1, "CREDIT", "10.00"),
                request("TXN-NEXT", 2, "DEBIT", "3.00")));

        assertThat(transactionRepository.findById("TXN-NEXT").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
        assertThat(transactionRepository.findById("TXN-NEXT").orElseThrow().getPendingReason())
                .isEqualTo(PendingReason.AWAITING_SEQUENCE);

        clock.advance(Duration.ofSeconds(30));
        resilienceJobs.runDueRetries();

        assertThat(transactionRepository.findById("TXN-ONCE").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(transactionRepository.findById("TXN-NEXT").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("7.00");
    }

    @Test
    void given_duplicate_while_original_awaits_retry_when_ingested_then_only_logs_duplicate() {
        ingestionService.ingestBatch(List.of(request("TXN-ONCE", 1, "CREDIT", "10.00")));
        TransactionRecord original = transactionRepository.findById("TXN-ONCE").orElseThrow();

        assertThat(ingestionService.ingest(request("TXN-ONCE", 1, "DEBIT", "90.00")))
                .isEqualTo(IngestionOutcome.DUPLICATE);

        assertThat(transactionRepository.findById("TXN-ONCE").orElseThrow().getStatus())
                .isEqualTo(original.getStatus());
        assertThat(ledgerRepository.count()).isZero();
        assertThat(duplicateLogRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void given_pending_sequence_times_out_when_missing_sequence_arrives_then_cursor_skips_timed_out_slot() {
        ingestionService.ingestBatch(List.of(
                request("TXN-1", 1, "CREDIT", "10.00"),
                request("TXN-3", 3, "CREDIT", "3.00")));
        clock.advance(Duration.ofMinutes(5).plusNanos(1));

        resilienceJobs.expirePendingSequences();

        TransactionRecord expired = transactionRepository.findById("TXN-3").orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(expired.getFailureReason()).isEqualTo(FailureReason.SEQUENCE_TIMEOUT);
        assertThat(expired.getSequenceNumber()).isEqualTo(3L);

        ingestionService.ingestBatch(List.of(request("TXN-4", 4, "CREDIT", "4.00")));
        ingestionService.ingestBatch(List.of(request("TXN-2", 2, "CREDIT", "2.00")));

        assertThat(transactionRepository.findById("TXN-2").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(transactionRepository.findById("TXN-3").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.FAILED);
        assertThat(transactionRepository.findById("TXN-4").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(accountRepository.findById("ACC-201").orElseThrow())
                .satisfies(account -> {
                    assertThat(account.getBalance()).isEqualByComparingTo("16.00");
                    assertThat(account.getNextExpectedSeq()).isEqualTo(5L);
                });
    }

    @Test
    void given_stuck_processing_transaction_when_reclaimed_then_processes_once() {
        ingestionService.ingest(request("TXN-STUCK", 1, "CREDIT", "8.00"));
        TransactionRecord transaction = transactionRepository.findById("TXN-STUCK").orElseThrow();
        transaction.markProcessing(START.minus(Duration.ofMinutes(3)));
        transactionRepository.saveAndFlush(transaction);

        resilienceJobs.reclaimStuckProcessing();
        TransactionRecord reclaimed = transactionRepository.findById("TXN-STUCK").orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(reclaimed.getPendingReason()).isEqualTo(PendingReason.AWAITING_RETRY);
        assertThat(reclaimed.getAttemptCount()).isEqualTo(1);
        assertThat(reclaimed.getNextAttemptAt()).isEqualTo(START);

        resilienceJobs.runDueRetries();

        assertThat(transactionRepository.findById("TXN-STUCK").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(ledgerRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo(BigDecimal.valueOf(8));
    }

    private static RawTransactionRequest request(
            String transactionId, long sequence, String type, String amount) {
        return new RawTransactionRequest(
                transactionId,
                "req-" + transactionId,
                "ACC-201",
                type,
                amount,
                "USD",
                Long.toString(sequence));
    }
}
