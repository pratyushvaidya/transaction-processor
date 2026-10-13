package com.assessment.transaction_processor.report;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProcessingReportJsonWriter {

    private final ObjectMapper objectMapper;

    public ProcessingReportJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(ProcessingReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize processing report", exception);
        }
    }
}
