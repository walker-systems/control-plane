package dev.jwalker.controlplane.api.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.jobs.model.Job;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.repository.JobRepository;
import dev.jwalker.controlplane.api.jobs.web.dto.JobCreateRequest;
import dev.jwalker.controlplane.api.jobs.web.dto.JobResponse;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
    private UserRepository userRepository;

    @InjectMocks
    private JobService jobService;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User(UUID.randomUUID(), "owner@example.com", "hashed", UserStatus.ACTIVE);
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
    void findById_returnsResponse_whenJobExists() {
        UUID jobId = UUID.randomUUID();
        Job job = new Job(jobId, owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.PENDING, JobPriority.MEDIUM, null, 3);
        job.setCreatedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.of(job));

        Optional<JobResponse> response = jobService.findById(jobId);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(jobId);
        assertThat(response.get().ownerEmail()).isEqualTo("owner@example.com");
    }

    @Test
    void findById_returnsEmpty_whenJobMissing() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.findByIdWithRelations(jobId)).thenReturn(Optional.empty());

        assertThat(jobService.findById(jobId)).isEmpty();
    }

    @Test
    void search_passesFiltersThrough_andMapsResults() {
        Pageable pageable = PageRequest.of(0, 10);
        Job job = new Job(UUID.randomUUID(), owner, null, JobType.CRM_SYNC, "{}",
                JobStatus.PENDING, JobPriority.MEDIUM, null, 3);
        job.setCreatedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        Page<Job> page = new PageImpl<>(List.of(job), pageable, 1);

        when(jobRepository.search(JobStatus.PENDING, JobType.CRM_SYNC, null, null, null, pageable))
                .thenReturn(page);

        Page<JobResponse> result = jobService.search(
                JobStatus.PENDING, JobType.CRM_SYNC, null, null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(job.getId());
        assertThat(result.getContent().get(0).ownerEmail()).isEqualTo("owner@example.com");
    }
}
