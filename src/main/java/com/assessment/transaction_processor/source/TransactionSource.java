package com.assessment.transaction_processor.source;

import com.assessment.transaction_processor.dto.RawTransactionRequest;
import java.util.List;

public interface TransactionSource {

    List<RawTransactionRequest> fetchBatch();
}
