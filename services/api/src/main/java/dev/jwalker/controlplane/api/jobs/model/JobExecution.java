package dev.jwalker.controlplane.api.jobs.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "job_executions")
public class JobExecution {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "worker_id", length = 255)
    private String workerId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private JobExecutionStatus status;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "output_summary", columnDefinition = "text")
    private String outputSummary;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JobExecution() {
    }

    public JobExecution(
            UUID id,
            Job job,
            String workerId,
            int attemptNumber,
            JobExecutionStatus status
    ) {
        this.id = id;
        this.job = job;
        this.workerId = workerId;
        this.attemptNumber = attemptNumber;
        this.status = status;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Job getJob() {
        return job;
    }

    public void setJob(Job job) {
        this.job = job;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public JobExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(JobExecutionStatus status) {
        this.status = status;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public OffsetDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void markRunning(String workerId, OffsetDateTime leaseExpiresAt) {
        this.workerId = workerId;
        this.status = JobExecutionStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public void markSucceeded(String outputSummary) {
        this.status = JobExecutionStatus.SUCCEEDED;
        this.finishedAt = OffsetDateTime.now();
        this.outputSummary = outputSummary;
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = JobExecutionStatus.FAILED;
        this.finishedAt = OffsetDateTime.now();
        this.errorMessage = errorMessage;
    }

    public boolean isTerminal() {
        return status == JobExecutionStatus.SUCCEEDED
                || status == JobExecutionStatus.FAILED
                || status == JobExecutionStatus.CANCELLED
                || status == JobExecutionStatus.TIMED_OUT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobExecution other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
