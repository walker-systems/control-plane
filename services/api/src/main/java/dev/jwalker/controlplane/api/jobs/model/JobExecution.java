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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "job_executions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
