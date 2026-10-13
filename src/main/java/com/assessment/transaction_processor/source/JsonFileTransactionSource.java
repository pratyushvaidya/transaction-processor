package com.assessment.transaction_processor.source;

import com.assessment.transaction_processor.config.TxnProperties;
import com.assessment.transaction_processor.dto.RawTransactionRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonFileTransactionSource implements TransactionSource {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final TxnProperties properties;

    public JsonFileTransactionSource(
            ResourceLoader resourceLoader, ObjectMapper objectMapper, TxnProperties properties) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<RawTransactionRequest> fetchBatch() {
        Resource resource = resourceLoader.getResource(properties.source().file());
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("Transaction source must contain a JSON array");
            }
            List<RawTransactionRequest> transactions = new ArrayList<>();
            for (JsonNode element : root) {
                transactions.add(readElement(element));
            }
            return List.copyOf(transactions);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read configured transaction source", exception);
        }
    }

    private RawTransactionRequest readElement(JsonNode element) {
        if (element == null || !element.isObject()) {
            return RawTransactionRequest.empty();
        }
        try {
            return objectMapper.treeToValue(element, RawTransactionRequest.class);
        } catch (JacksonException exception) {
            return RawTransactionRequest.empty();
        }
    }
}
