package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Placeholder JobHandler implementations — one @Component per JobType so
// Spring registers them individually into JobHandlerRegistry. They all
// share LoggingJobHandler's behavior: log the job and return a short
// summary. Real per-type handlers can replace these one at a time
// without touching the executor or the registry.
@Slf4j
public abstract class LoggingJobHandler implements JobHandler {

    @Override
    public String handle(Job job) {
        log.info(
                "LoggingJobHandler processing job {} of type {} (payload={})",
                job.getId(),
                getSupportedType(),
                job.getPayloadJson());
        return "handled by " + getClass().getSimpleName();
    }
}

@Component
class CustomerExportPlaceholderHandler extends LoggingJobHandler {
    @Override
    public JobType getSupportedType() {
        return JobType.CUSTOMER_EXPORT;
    }
}

@Component
class StaleAccountCleanupPlaceholderHandler extends LoggingJobHandler {
    @Override
    public JobType getSupportedType() {
        return JobType.STALE_ACCOUNT_CLEANUP;
    }
}

@Component
class SuspiciousAccountScanPlaceholderHandler extends LoggingJobHandler {
    @Override
    public JobType getSupportedType() {
        return JobType.SUSPICIOUS_ACCOUNT_SCAN;
    }
}

@Component
class CrmSyncPlaceholderHandler extends LoggingJobHandler {
    @Override
    public JobType getSupportedType() {
        return JobType.CRM_SYNC;
    }
}
