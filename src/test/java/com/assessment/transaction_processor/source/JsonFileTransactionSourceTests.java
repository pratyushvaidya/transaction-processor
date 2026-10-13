package com.assessment.transaction_processor.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transaction_processor.dto.RawTransactionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JsonFileTransactionSourceTests {

    @Autowired
    private JsonFileTransactionSource source;

    @Test
    void given_file_with_missing_transaction_identifier_when_fetched_then_keeps_other_requests() {
        List<RawTransactionRequest> batch = source.fetchBatch();

        assertThat(batch).hasSize(10);
        assertThat(batch.get(0).transactionId()).isEqualTo("TXN-2002");
        assertThat(batch.get(9).transactionId()).isNull();
        assertThat(batch.get(9).requestId()).isEqualTo("REQ-MALFORMED");
    }
}
