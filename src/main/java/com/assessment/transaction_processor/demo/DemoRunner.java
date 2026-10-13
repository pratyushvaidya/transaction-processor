package com.assessment.transaction_processor.demo;

import com.assessment.transaction_processor.report.ProcessingReport;
import com.assessment.transaction_processor.report.ProcessingReportJsonWriter;
import com.assessment.transaction_processor.report.ProcessingReportRenderer;
import com.assessment.transaction_processor.report.ProcessingReportService;
import com.assessment.transaction_processor.service.ResilienceJobs;
import com.assessment.transaction_processor.service.IngestionService;
import com.assessment.transaction_processor.source.TransactionSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoRunner implements CommandLineRunner {

    private static final long MAX_WAIT_SECONDS = 30L;

    private final TransactionSource transactionSource;
    private final IngestionService ingestionService;
    private final ProcessingReportService reportService;
    private final ProcessingReportRenderer renderer;
    private final ProcessingReportJsonWriter jsonWriter;
    private final ResilienceJobs resilienceJobs;
    private final Clock clock;

    public DemoRunner(
            TransactionSource transactionSource,
            IngestionService ingestionService,
            ProcessingReportService reportService,
            ProcessingReportRenderer renderer,
            ProcessingReportJsonWriter jsonWriter,
            ResilienceJobs resilienceJobs,
            Clock clock) {
        this.transactionSource = transactionSource;
        this.ingestionService = ingestionService;
        this.reportService = reportService;
        this.renderer = renderer;
        this.jsonWriter = jsonWriter;
        this.resilienceJobs = resilienceJobs;
        this.clock = clock;
    }

    @Override
    public void run(String... args) throws Exception {
        ingestionService.ingestBatch(transactionSource.fetchBatch());
        Instant deadline = clock.instant().plusSeconds(MAX_WAIT_SECONDS);
        ProcessingReport report = reportService.createReport();
        while (report.activeCount() > 0 && clock.instant().isBefore(deadline)) {
            resilienceJobs.runDueRetries();
            resilienceJobs.expirePendingSequences();
            resilienceJobs.reclaimStuckProcessing();
            Thread.sleep(1_000L);
            report = reportService.createReport();
        }

        System.out.println(renderer.render(report));
        Path output = Path.of("output");
        Files.createDirectories(output);
        Files.writeString(output.resolve("processing-report.json"), jsonWriter.write(report));
    }
}
