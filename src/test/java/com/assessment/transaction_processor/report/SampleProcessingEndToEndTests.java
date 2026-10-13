package com.assessment.transaction_processor.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transaction_processor.TransactionProcessorApplication;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.LedgerEntryRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import com.assessment.transaction_processor.service.IngestionService;
import com.assessment.transaction_processor.service.ResilienceJobs;
import com.assessment.transaction_processor.source.TransactionSource;
import com.assessment.transaction_processor.support.MutableClock;
import com.assessment.transaction_processor.support.MutableClockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        classes = TransactionProcessorApplication.class,
        properties = {
            "txn.fault-injection.enabled=true",
            "txn.fault-injection.failures.TXN-2005=1",
            "txn.retry.delay=2s",
            "txn.pending.timeout=10s"
        })
@Import(MutableClockConfiguration.class)
class SampleProcessingEndToEndTests {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private TransactionSource source;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ResilienceJobs jobs;

    @Autowired
    private ProcessingReportService reportService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRecordRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private DuplicateRequestLogRepository duplicateRepository;

    @Autowired
    private RejectedRecordRepository rejectedRepository;

    @BeforeEach
    void cleanDatabaseAndSetClock() {
        clock.setInstant(START);
        ledgerRepository.deleteAll();
        duplicateRepository.deleteAll();
        rejectedRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void given_sample_batch_when_processed_and_timeouts_run_then_matches_expected_demo_state() {
        ingestionService.ingestBatch(source.fetchBatch());

        clock.advance(Duration.ofSeconds(2));
        jobs.runDueRetries();
        clock.advance(Duration.ofSeconds(8).plusNanos(1));
        jobs.expirePendingSequences();

        ProcessingReport report = reportService.createReport();
        assertThat(report.countsByStatus())
                .containsEntry(TransactionStatus.PROCESSED, 5L)
                .containsEntry(TransactionStatus.FAILED, 4L)
                .containsEntry(TransactionStatus.DUPLICATE, 1L);
        assertThat(report.failedByReason())
                .containsEntry(FailureReason.VALIDATION_FAILED, 1L)
                .containsEntry(FailureReason.INSUFFICIENT_FUNDS, 1L)
                .containsEntry(FailureReason.SEQUENCE_TIMEOUT, 1L)
                .containsEntry(FailureReason.MALFORMED, 1L);
        assertThat(report.rejectedRecords()).hasSize(1);
        assertThat(report.retriedTransactions()).isEqualTo(1);
        assertThat(report.totalRetryAttempts()).isEqualTo(1);
        assertThat(report.ledgerBalancesMatch()).isTrue();

        assertBalance("ACC-201", "85.00", 4L);
        assertBalance("ACC-202", "10.00", 3L);
        assertBalance("ACC-203", "500.00", 2L);
        assertBalance("ACC-204", "0.00", 2L);
        assertBalance("ACC-205", "0.00", 1L);
        assertThat(transactionRepository.count()).isEqualTo(8);
        assertThat(ledgerRepository.count()).isEqualTo(5);
        TransactionRecord retried = transactionRepository.findById("TXN-2005").orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(retried.getAttemptCount()).isEqualTo(2);
        assertThat(transactionRepository.findById("TXN-2008").orElseThrow().getFailureReason())
                .isEqualTo(FailureReason.SEQUENCE_TIMEOUT);
    }

    private void assertBalance(String id, String balance, long cursor) {
        Account account = accountRepository.findById(id).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(balance);
        assertThat(account.getNextExpectedSeq()).isEqualTo(cursor);
    }
}
