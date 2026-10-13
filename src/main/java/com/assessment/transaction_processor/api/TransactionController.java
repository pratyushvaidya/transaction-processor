package com.assessment.transaction_processor.api;

import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.IngestionOutcome;
import com.assessment.transaction_processor.report.ProcessingReport;
import com.assessment.transaction_processor.report.ProcessingReportService;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import com.assessment.transaction_processor.service.IngestionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final IngestionService ingestionService;
    private final TransactionRecordRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final ProcessingReportService reportService;

    public TransactionController(
            IngestionService ingestionService,
            TransactionRecordRepository transactionRepository,
            AccountRepository accountRepository,
            ProcessingReportService reportService) {
        this.ingestionService = ingestionService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.reportService = reportService;
    }

    @PostMapping("/transactions")
    public List<IngestionOutcome> ingest(@RequestBody List<RawTransactionRequest> requests) {
        if (requests == null) {
            throw new MalformedRequestException();
        }
        return ingestionService.ingestBatch(requests.stream()
                .map(request -> request == null ? RawTransactionRequest.empty() : request)
                .toList());
    }

    @GetMapping("/transactions/{id}")
    public TransactionView getTransaction(@PathVariable String id) {
        TransactionRecord transaction = transactionRepository.findById(id)
                .orElseThrow(ResourceNotFoundException::new);
        return TransactionView.from(transaction);
    }

    @GetMapping("/accounts/{id}")
    public AccountView getAccount(@PathVariable String id) {
        Account account = accountRepository.findById(id).orElseThrow(ResourceNotFoundException::new);
        return AccountView.from(account);
    }

    @GetMapping("/report")
    public ProcessingReport getReport() {
        return reportService.createReport();
    }
}
