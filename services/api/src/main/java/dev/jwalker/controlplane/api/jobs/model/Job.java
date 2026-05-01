package dev.jwalker.controlplane.api.jobs.model;

import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.users.model.User;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_schedule_id")
    private JobSchedule sourceSchedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private JobType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private JobPriority priority;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Job(
            UUID id,
            User owner,
            JobSchedule sourceSchedule,
            JobType type,
            String payloadJson,
            JobStatus status,
            JobPriority priority,
            String idempotencyKey,
            int maxRetries
    ) {
        this.id = id;
        this.owner = owner;
        this.sourceSchedule = sourceSchedule;
        this.type = type;
        this.payloadJson = payloadJson;
        this.status = status;
        this.priority = priority;
        this.idempotencyKey = idempotencyKey;
        this.maxRetries = maxRetries;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();

        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isScheduledJob() {
        return sourceSchedule != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Job other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
