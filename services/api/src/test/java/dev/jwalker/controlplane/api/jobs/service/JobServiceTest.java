package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobExecutionRepository.JobAttemptCount;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository.JobStatusCount;
import dev.jwalker.controlplane.api.jobs.web.dto.JobCreateRequest;
import dev.jwalker.controlplane.api.jobs.web.dto.JobResponse;
import dev.jwalker.controlplane.api.jobs.web.dto.JobStatsResponse;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobExecutionRepository jobExecutionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditEventService auditEventService;

    @InjectMocks
    private JobService jobService;

    private User owner;
    private AuthenticatedCaller ownerCaller;
    private AuthenticatedCaller otherUserCaller;
    private AuthenticatedCaller operatorCaller;

    @BeforeEach
    void setUp() {
        owner = new User(UUID.randomUUID(), "owner@example.com", "hashed", UserStatus.ACTIVE);
        ownerCaller = new AuthenticatedCaller(owner.getId(), Set.of("USER"));
        otherUserCaller = new AuthenticatedCaller(UUID.randomUUID(), Set.of("USER"));
        operatorCaller = new AuthenticatedCaller(UUID.randomUUID(), Set.of("OPERATOR"));
    }

    @Test
    void create_persistsJobAndReturnsResponse() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(UUID.randomUUID());
            j.setCreatedAt(OffsetDateTime.now());
            j.setUpdatedAt(OffsetDateTime.now());
            return j;
        });

        JobCreateRequest request = new JobCreateRequest(
                JobType.CRM_SYNC, "{\"k\":1}", JobPriority.HIGH, 5, "idem-1");

        JobResponse response = jobService.create(owner.getId(), request);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job saved = captor.getValue();
        assertThat(saved.getOwner()).isEqualTo(owner);
        assertThat(saved.getType()).isEqualTo(JobType.CRM_SYNC);
        assertThat(saved.getPayloadJson()).isEqualTo("{\"k\":1}");
        assertThat(saved.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(saved.getPriority()).isEqualTo(JobPriority.HIGH);
        assertThat(saved.getMaxRetries()).isEqualTo(5);
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-1");

        assertThat(response.ownerEmail()).isEqualTo("owner@example.com");
        assertThat(response.status()).isEqualTo(JobStatus.PENDING);

        verify(auditEventService).record(
                eq(AuditEventType.JOB_CREATED), eq("Job"), eq(saved.getId()), any());
    }

    @Test
    void create_appliesDefaults_whenOptionalFieldsOmitted() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        JobCreateRequest request = new JobCreateRequest(
                JobType.CRM_SYNC, "{}", null, null, null);

        jobService.create(owner.getId(), request);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job saved = captor.getValue();
        assertThat(saved.getPriority()).isEqualTo(JobPriority.MEDIUM);
        assertThat(saved.getMaxRetries()).isEqualTo(3);
        assertThat(saved.getIdempotencyKey()).isNull();
    }

    @Test
    void create_alwaysSetsStatusToPending_ignoringInput() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        jobService.create(owner.getId(), new JobCreateRequest(
                JobType.CRM_SYNC, "{}", JobPriority.LOW, 1, null));

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void create_throws_whenAuthenticatedUserNotFound() {
        UUID missing = UUID.randomUUID();
        when(userRepository.findById(missing)).thenReturn(Optional.empty());

        JobCreateRequest request = new JobCreateRequest(JobType.CRM_SYNC, "{}", null, null, null);

        assertThatThrownBy(() -> jobService.create(missing, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.toString());
        verify(jobRepository, never()).save(any());
    }

    @Test
    void findById_returnsResponse_whenCallerIsOwner() {
        UUID jobId = UUID.randomUUID();
        Job job = pendingJob(jobId);
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(job));

        Optional<JobResponse> response = jobService.findById(jobId, ownerCaller);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(jobId);
    }

    @Test
    void findById_returnsResponse_whenCallerIsOperator() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(pendingJob(jobId)));

        assertThat(jobService.findById(jobId, operatorCaller)).isPresent();
    }

    @Test
    void findById_returnsEmpty_whenCallerIsUnprivilegedNonOwner() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(pendingJob(jobId)));

        assertThat(jobService.findById(jobId, otherUserCaller)).isEmpty();
    }

    @Test
    void findById_returnsEmpty_whenJobMissing() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.empty());

        assertThat(jobService.findById(jobId, ownerCaller)).isEmpty();
    }

    @Test
    void cancel_transitionsPendingToCancelled_whenCallerIsOwner() {
        UUID jobId = UUID.randomUUID();
        Job job = pendingJob(jobId);
        when(jobRepository.findByIdWithRelationsForUpdate(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Optional<JobResponse> response = jobService.cancel(jobId, ownerCaller);

        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo(JobStatus.CANCELLED);
        // Cancel-from-PENDING now also stamps the request timestamp for
        // uniform "when was cancel requested" observability.
        assertThat(response.get().cancelRequestedAt()).isNotNull();

        verify(auditEventService).record(
                eq(AuditEventType.JOB_CANCELLED), eq("Job"), eq(jobId), any());
        // Cancel must use the locking read; the non-locking variant would let
        // an in-flight executor tx race the status check.
        verify(jobRepository, never()).findByIdWithRelations(any());
    }

    @Test
    void cancel_returnsEmpty_whenCallerIsUnprivilegedNonOwner() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByIdWithRelationsForUpdate(jobId)).thenReturn(Optional.of(pendingJob(jobId)));

        assertThat(jobService.cancel(jobId, otherUserCaller)).isEmpty();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void cancel_setsCancelRequestedAt_andKeepsStatus_whenJobIsRunning() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.RUNNING, JobPriority.MEDIUM, null, 3);
        when(jobRepository.findByIdWithRelationsForUpdate(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Optional<JobResponse> response = jobService.cancel(jobId, ownerCaller);

        assertThat(response).isPresent();
        // Deferred cancel: status stays RUNNING; executor/watchdog will honor
        // cancelRequestedAt when finalizing the outcome.
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getCancelRequestedAt()).isNotNull();
        // No JOB_CANCELLED audit at the API layer for the running case;
        // the executor emits it when the transition actually happens.
        verify(auditEventService, never()).record(any(), any(), any(), any());
    }

    @Test
    void cancel_throwsJobStateException_whenJobIsSucceeded() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.SUCCEEDED, JobPriority.MEDIUM, null, 3);
        when(jobRepository.findByIdWithRelationsForUpdate(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobService.cancel(jobId, ownerCaller))
                .isInstanceOf(JobStateException.class)
                .extracting(e -> ((JobStateException) e).reason())
                .isEqualTo(JobStateException.Reason.CANNOT_CANCEL);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void cancel_throwsJobStateException_whenJobIsDeadLettered() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.DEAD_LETTER, JobPriority.MEDIUM, null, 3);
        when(jobRepository.findByIdWithRelationsForUpdate(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobService.cancel(jobId, ownerCaller))
                .isInstanceOf(JobStateException.class)
                .extracting(e -> ((JobStateException) e).reason())
                .isEqualTo(JobStateException.Reason.CANNOT_CANCEL);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void cancel_returnsEmpty_whenJobMissing() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByIdWithRelationsForUpdate(jobId)).thenReturn(Optional.empty());

        assertThat(jobService.cancel(jobId, ownerCaller)).isEmpty();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void retry_transitionsDeadLetterToPending_whenCallerIsOwner() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.DEAD_LETTER, JobPriority.MEDIUM, null, 3);
        job.setCreatedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        Optional<JobResponse> response = jobService.retry(jobId, ownerCaller);

        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo(JobStatus.PENDING);

        verify(auditEventService).record(
                eq(AuditEventType.JOB_RETRIED), eq("Job"), eq(jobId), any());
    }

    @Test
    void retry_succeeds_whenCallerIsOperator() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.DEAD_LETTER, JobPriority.MEDIUM, null, 3);
        job.setCreatedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(job)).thenReturn(job);

        assertThat(jobService.retry(jobId, operatorCaller)).isPresent();
    }

    @Test
    void retry_returnsEmpty_whenCallerIsUnprivilegedNonOwner() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.DEAD_LETTER, JobPriority.MEDIUM, null, 3);
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(job));

        assertThat(jobService.retry(jobId, otherUserCaller)).isEmpty();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void retry_throwsJobStateException_whenJobIsSucceeded() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.SUCCEEDED, JobPriority.MEDIUM, null, 3);
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobService.retry(jobId, ownerCaller))
                .isInstanceOf(JobStateException.class)
                .extracting(e -> ((JobStateException) e).reason())
                .isEqualTo(JobStateException.Reason.CANNOT_RETRY);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void retry_returnsEmpty_whenJobMissing() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.empty());

        assertThat(jobService.retry(jobId, ownerCaller)).isEmpty();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void search_forUser_silentlyForcesOwnerIdToSelf() {
        Pageable pageable = PageRequest.of(0, 10);
        UUID someoneElse = UUID.randomUUID();
        Page<Job> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(jobRepository.search(null, null, null, owner.getId(), null, pageable))
                .thenReturn(emptyPage);

        jobService.search(null, null, null, someoneElse, null, pageable, ownerCaller);

        verify(jobRepository).search(null, null, null, owner.getId(), null, pageable);
    }

    @Test
    void search_forOperator_respectsOwnerIdFilter() {
        Pageable pageable = PageRequest.of(0, 10);
        UUID someoneElse = UUID.randomUUID();
        Page<Job> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(jobRepository.search(null, null, null, someoneElse, null, pageable))
                .thenReturn(emptyPage);

        jobService.search(null, null, null, someoneElse, null, pageable, operatorCaller);

        verify(jobRepository).search(null, null, null, someoneElse, null, pageable);
    }

    @Test
    void create_returnsResponseWithZeroAttemptCount() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId(UUID.randomUUID());
            j.setAvailableAt(OffsetDateTime.now());
            return j;
        });

        JobResponse response = jobService.create(
                owner.getId(),
                new JobCreateRequest(JobType.CRM_SYNC, "{}", null, null, null));

        assertThat(response.attemptCount()).isZero();
        assertThat(response.availableAt()).isNotNull();
        // A fresh job never queries the executions repo for its own count.
        verify(jobExecutionRepository, never()).countByJob_Id(any());
    }

    @Test
    void findById_populatesAttemptCountFromRepository() {
        UUID jobId = UUID.randomUUID();
        Job job = pendingJob(jobId);
        job.setAvailableAt(OffsetDateTime.now());
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(job));
        when(jobExecutionRepository.countByJob_Id(jobId)).thenReturn(2L);

        JobResponse response = jobService.findById(jobId, ownerCaller).orElseThrow();

        assertThat(response.attemptCount()).isEqualTo(2L);
        assertThat(response.availableAt()).isEqualTo(job.getAvailableAt());
    }

    @Test
    void search_bulkFetchesAttemptCountsAndMapsPerJob() {
        Pageable pageable = PageRequest.of(0, 10);
        UUID jobIdA = UUID.randomUUID();
        UUID jobIdB = UUID.randomUUID();
        Job jobA = pendingJob(jobIdA);
        Job jobB = pendingJob(jobIdB);
        Page<Job> page = new PageImpl<>(List.of(jobA, jobB), pageable, 2);
        when(jobRepository.search(null, null, null, owner.getId(), null, pageable))
                .thenReturn(page);

        JobAttemptCount countForA = org.mockito.Mockito.mock(JobAttemptCount.class);
        when(countForA.getJobId()).thenReturn(jobIdA);
        when(countForA.getAttemptCount()).thenReturn(3L);
        // Job B has no execution rows — omitted from grouped-count results.
        when(jobExecutionRepository.countAttemptsByJobIds(List.of(jobIdA, jobIdB)))
                .thenReturn(List.of(countForA));

        Page<JobResponse> result = jobService.search(
                null, null, null, null, null, pageable, ownerCaller);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).id()).isEqualTo(jobIdA);
        assertThat(result.getContent().get(0).attemptCount()).isEqualTo(3L);
        assertThat(result.getContent().get(1).id()).isEqualTo(jobIdB);
        assertThat(result.getContent().get(1).attemptCount()).isZero();
        // Single bulk query — no per-job COUNT calls.
        verify(jobExecutionRepository, never()).countByJob_Id(any());
    }

    // --- stats -----------------------------------------------------

    @Test
    void stats_filtersToOwnerJobs_forUserRole() {
        when(jobRepository.countByStatus(owner.getId())).thenReturn(List.of(
                statusCount(JobStatus.PENDING, 2L),
                statusCount(JobStatus.RUNNING, 1L),
                statusCount(JobStatus.SUCCEEDED, 5L)));

        JobStatsResponse resp = jobService.stats(ownerCaller);

        // Only the owner's counts were requested — no null-filter call.
        verify(jobRepository).countByStatus(owner.getId());
        verify(jobRepository, never()).countByStatus(null);
        assertThat(resp.counts()).containsEntry(JobStatus.PENDING, 2L);
        assertThat(resp.counts()).containsEntry(JobStatus.RUNNING, 1L);
        assertThat(resp.counts()).containsEntry(JobStatus.SUCCEEDED, 5L);
    }

    @Test
    void stats_returnsAllJobs_forPrivilegedCaller() {
        when(jobRepository.countByStatus(null)).thenReturn(List.of(
                statusCount(JobStatus.PENDING, 10L),
                statusCount(JobStatus.DEAD_LETTER, 3L)));

        JobStatsResponse resp = jobService.stats(operatorCaller);

        // Null owner filter means "across all owners".
        verify(jobRepository).countByStatus(null);
        assertThat(resp.counts()).containsEntry(JobStatus.PENDING, 10L);
        assertThat(resp.counts()).containsEntry(JobStatus.DEAD_LETTER, 3L);
    }

    @Test
    void stats_populatesZerosForMissingStatuses() {
        // Repo returns only PENDING; every other status must show 0
        // in the response so the UI can render tiles without null
        // checks.
        when(jobRepository.countByStatus(owner.getId())).thenReturn(List.of(
                statusCount(JobStatus.PENDING, 4L)));

        JobStatsResponse resp = jobService.stats(ownerCaller);

        for (JobStatus status : JobStatus.values()) {
            assertThat(resp.counts()).containsKey(status);
        }
        assertThat(resp.counts().get(JobStatus.PENDING)).isEqualTo(4L);
        assertThat(resp.counts().get(JobStatus.RUNNING)).isEqualTo(0L);
        assertThat(resp.counts().get(JobStatus.CANCELLED)).isEqualTo(0L);
    }

    private static JobStatusCount statusCount(JobStatus status, long count) {
        return new JobStatusCount() {
            @Override public JobStatus getStatus() { return status; }
            @Override public long getCount() { return count; }
        };
    }

    private Job pendingJob(UUID jobId) {
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.PENDING, JobPriority.MEDIUM, null, 3);
        job.setCreatedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        return job;
    }
}
