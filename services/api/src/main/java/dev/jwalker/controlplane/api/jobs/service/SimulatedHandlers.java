package dev.jwalker.controlplane.api.jobs.service;

import dev.jwalker.controlplane.api.jobs.model.JobType;
import org.springframework.stereotype.Component;

// Four demo handlers, one per JobType. Each extends SimulatedWorkHandler
// with its own work profile (duration range, failure rate, log flavor)
// so the demo dashboard shows a mix of durations and outcomes rather
// than four identical rows. Replace one at a time as real handlers land.

@Component
class CustomerExportSimulatedHandler extends SimulatedWorkHandler {
    @Override public JobType getSupportedType() { return JobType.CUSTOMER_EXPORT; }
    @Override protected int getMinChunks() { return 3; }
    @Override protected int getMaxChunks() { return 5; }
    @Override protected double getFailureRate() { return 0.10; }
    @Override protected String getProgressLabel() { return "exporting customer batch"; }
    @Override protected String getSuccessSummary(int chunks) {
        return "exported " + (chunks * 250) + " customer rows";
    }
}

@Component
class StaleAccountCleanupSimulatedHandler extends SimulatedWorkHandler {
    @Override public JobType getSupportedType() { return JobType.STALE_ACCOUNT_CLEANUP; }
    @Override protected int getMinChunks() { return 2; }
    @Override protected int getMaxChunks() { return 4; }
    @Override protected double getFailureRate() { return 0.15; }
    @Override protected String getProgressLabel() { return "scanning stale accounts"; }
    @Override protected String getSuccessSummary(int chunks) {
        return "cleaned " + (chunks * 12) + " stale accounts";
    }
}

@Component
class SuspiciousAccountScanSimulatedHandler extends SimulatedWorkHandler {
    @Override public JobType getSupportedType() { return JobType.SUSPICIOUS_ACCOUNT_SCAN; }
    // Highest failure rate — makes DEAD_LETTER outcomes visible in the demo
    // without being so common that they overwhelm the dashboard.
    @Override protected int getMinChunks() { return 5; }
    @Override protected int getMaxChunks() { return 8; }
    @Override protected double getFailureRate() { return 0.25; }
    @Override protected String getProgressLabel() { return "analyzing risk signals"; }
    @Override protected String getSuccessSummary(int chunks) {
        return "flagged " + (chunks * 3) + " suspicious accounts";
    }
}

@Component
class CrmSyncSimulatedHandler extends SimulatedWorkHandler {
    @Override public JobType getSupportedType() { return JobType.CRM_SYNC; }
    @Override protected int getMinChunks() { return 4; }
    @Override protected int getMaxChunks() { return 7; }
    @Override protected double getFailureRate() { return 0.15; }
    @Override protected String getProgressLabel() { return "syncing contacts to CRM"; }
    @Override protected String getSuccessSummary(int chunks) {
        return "synced " + (chunks * 100) + " contacts";
    }
}
