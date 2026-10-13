package com.assessment.transaction_processor.config;

import com.assessment.transaction_processor.service.ResilienceJobs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "txn.scheduling",
        name = "enabled",
        havingValue = "true")
public class ScheduledResilienceJobs {

    private final ResilienceJobs jobs;

    public ScheduledResilienceJobs(ResilienceJobs jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${txn.scheduler.poll-interval:5s}")
    public void runDueRetries() {
        jobs.runDueRetries();
    }

    @Scheduled(fixedDelayString = "${txn.scheduler.poll-interval:5s}")
    public void expirePendingSequences() {
        jobs.expirePendingSequences();
    }

    @Scheduled(fixedDelayString = "${txn.scheduler.poll-interval:5s}")
    public void reclaimStuckProcessing() {
        jobs.reclaimStuckProcessing();
    }
}
