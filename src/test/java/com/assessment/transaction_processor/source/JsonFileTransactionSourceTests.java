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
    void given_file_with_malformed_element_when_fetched_then_returns_null_fields_for_only_that_element() {
        List<RawTransactionRequest> batch = source.fetchBatch();

        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).transactionId()).isEqualTo("sample-1");
        assertThat(batch.get(1)).isEqualTo(RawTransactionRequest.empty());
    }
}
