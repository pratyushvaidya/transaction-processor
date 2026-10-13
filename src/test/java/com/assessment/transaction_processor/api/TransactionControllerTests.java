package com.assessment.transaction_processor.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.transaction_processor.TransactionProcessorApplication;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.LedgerEntryRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TransactionProcessorApplication.class)
@AutoConfigureMockMvc
class TransactionControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRecordRepository transactionRepository;

    @Autowired
    private DuplicateRequestLogRepository duplicateRepository;

    @Autowired
    private RejectedRecordRepository rejectedRepository;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @BeforeEach
    void cleanDatabase() {
        ledgerRepository.deleteAll();
        rejectedRepository.deleteAll();
        duplicateRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void given_valid_batch_when_posted_then_returns_per_record_outcomes() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{
                                  "transactionId":"api-1",
                                  "requestId":"request-1",
                                  "accountId":"API-ACCOUNT",
                                  "transactionType":"CREDIT",
                                  "amount":"3.00",
                                  "currency":"USD",
                                  "sequenceNumber":"1"
                                }]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("PENDING"));
    }

    @Test
    void given_malformed_json_body_when_posted_then_returns_safe_client_error() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is invalid"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void given_null_batch_body_when_posted_then_returns_safe_client_error() throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void given_unknown_transaction_when_requested_then_returns_safe_not_found_body() throws Exception {
        mockMvc.perform(get("/api/transactions/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Requested resource was not found"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void given_account_when_requested_then_returns_account_view() throws Exception {
        accountRepository.saveAndFlush(new Account("API-ACCOUNT"));

        mockMvc.perform(get("/api/accounts/API-ACCOUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("API-ACCOUNT"))
                .andExpect(jsonPath("$.nextExpectedSeq").value(1));
    }
}
