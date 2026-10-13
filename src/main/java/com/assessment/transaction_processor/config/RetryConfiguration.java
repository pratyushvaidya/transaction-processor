package com.assessment.transaction_processor.config;

import com.assessment.transaction_processor.service.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryConfiguration {

    @Bean
    RetryPolicy retryPolicy(TxnProperties properties) {
        return new RetryPolicy(properties.retry().delay(), properties.retry().maxAttempts());
    }
}
