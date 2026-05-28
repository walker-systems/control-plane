package dev.jwalker.controlplane.api.schedules.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.model.JobSchedule;
import dev.jwalker.controlplane.api.schedules.repository.JobScheduleRepository;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleCreateRequest;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleResponse;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class JobScheduleServiceTest {

    @Mock
    private JobScheduleRepository jobScheduleRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private JobScheduleService jobScheduleService;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User(UUID.randomUUID(), "owner@example.com", "hashed", UserStatus.ACTIVE);
    }

    @Test
    void create_persistsScheduleAndReturnsResponse() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(jobScheduleRepository.saveAndFlush(any(JobSchedule.class))).thenAnswer(inv -> {
            JobSchedule s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            s.setCreatedAt(OffsetDateTime.now());
            s.setUpdatedAt(OffsetDateTime.now());
            return s;
        });

        JobScheduleCreateRequest request = new JobScheduleCreateRequest(
                "Daily CRM Sync", JobType.CRM_SYNC, "{}", JobPriority.HIGH, 5,
                "0 0 0 * * *", "America/Los_Angeles");

        JobScheduleResponse response = jobScheduleService.create(owner.getId(), request);

        ArgumentCaptor<JobSchedule> captor = ArgumentCaptor.forClass(JobSchedule.class);
        verify(jobScheduleRepository).saveAndFlush(captor.capture());
        JobSchedule saved = captor.getValue();
        assertThat(saved.getOwner()).isEqualTo(owner);
        assertThat(saved.getName()).isEqualTo("Daily CRM Sync");
        assertThat(saved.getCronExpression()).isEqualTo("0 0 0 * * *");
        assertThat(saved.getTimezone()).isEqualTo("America/Los_Angeles");
        assertThat(saved.getPriority()).isEqualTo(JobPriority.HIGH);
        assertThat(saved.getMaxRetries()).isEqualTo(5);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getNextRunAt()).isNotNull();

        assertThat(response.name()).isEqualTo("Daily CRM Sync");
        assertThat(response.enabled()).isTrue();
        assertThat(response.ownerEmail()).isEqualTo("owner@example.com");
    }

    @Test
    void create_appliesDefaults_whenOptionalFieldsOmitted() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(jobScheduleRepository.saveAndFlush(any(JobSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

        JobScheduleCreateRequest request = new JobScheduleCreateRequest(
                "Default Test", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "UTC");

        jobScheduleService.create(owner.getId(), request);

        ArgumentCaptor<JobSchedule> captor = ArgumentCaptor.forClass(JobSchedule.class);
        verify(jobScheduleRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(JobPriority.MEDIUM);
        assertThat(captor.getValue().getMaxRetries()).isEqualTo(3);
    }

    @Test
    void create_throwsInvalidCron_whenExpressionMalformed() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        JobScheduleCreateRequest request = new JobScheduleCreateRequest(
                "Bad Cron", JobType.CRM_SYNC, "{}", null, null, "not-a-cron", "UTC");

        assertThatThrownBy(() -> jobScheduleService.create(owner.getId(), request))
                .isInstanceOf(InvalidScheduleConfigException.class)
                .extracting(e -> ((InvalidScheduleConfigException) e).reason())
                .isEqualTo(InvalidScheduleConfigException.Reason.INVALID_CRON);
        verify(jobScheduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_throwsInvalidTimezone_whenZoneUnknown() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        JobScheduleCreateRequest request = new JobScheduleCreateRequest(
                "Bad Zone", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "Mars/Olympus");

        assertThatThrownBy(() -> jobScheduleService.create(owner.getId(), request))
                .isInstanceOf(InvalidScheduleConfigException.class)
                .extracting(e -> ((InvalidScheduleConfigException) e).reason())
                .isEqualTo(InvalidScheduleConfigException.Reason.INVALID_TIMEZONE);
        verify(jobScheduleRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_throwsDuplicateName_whenDbRejectsUniqueConstraint() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(jobScheduleRepository.saveAndFlush(any(JobSchedule.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        JobScheduleCreateRequest request = new JobScheduleCreateRequest(
                "Daily Sync", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "UTC");

        assertThatThrownBy(() -> jobScheduleService.create(owner.getId(), request))
                .isInstanceOf(InvalidScheduleConfigException.class)
                .extracting(e -> ((InvalidScheduleConfigException) e).reason())
                .isEqualTo(InvalidScheduleConfigException.Reason.DUPLICATE_NAME);
    }

    @Test
    void findById_returnsResponse_whenScheduleExists() {
        UUID scheduleId = UUID.randomUUID();
        JobSchedule schedule = new JobSchedule(
                scheduleId, owner, "Test", JobType.CRM_SYNC, "{}",
                JobPriority.MEDIUM, 3, "0 0 * * * *", "UTC");
        schedule.setCreatedAt(OffsetDateTime.now());
        schedule.setUpdatedAt(OffsetDateTime.now());
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.of(schedule));

        Optional<JobScheduleResponse> response = jobScheduleService.findById(scheduleId);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(scheduleId);
        assertThat(response.get().name()).isEqualTo("Test");
        assertThat(response.get().ownerEmail()).isEqualTo("owner@example.com");
    }

    @Test
    void findById_returnsEmpty_whenScheduleMissing() {
        UUID scheduleId = UUID.randomUUID();
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.empty());

        assertThat(jobScheduleService.findById(scheduleId)).isEmpty();
    }

    @Test
    void pause_disablesSchedule_andClearsNextRunAt() {
        UUID scheduleId = UUID.randomUUID();
        JobSchedule s = enabledSchedule(scheduleId);
        s.setNextRunAt(OffsetDateTime.now().plusHours(1));
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.of(s));
        when(jobScheduleRepository.save(s)).thenReturn(s);

        Optional<JobScheduleResponse> response = jobScheduleService.pause(scheduleId);

        assertThat(response).isPresent();
        assertThat(response.get().enabled()).isFalse();
        assertThat(response.get().nextRunAt()).isNull();
        assertThat(s.isEnabled()).isFalse();
        assertThat(s.getNextRunAt()).isNull();
    }

    @Test
    void pause_isIdempotent_whenAlreadyDisabled() {
        UUID scheduleId = UUID.randomUUID();
        JobSchedule s = enabledSchedule(scheduleId);
        s.disable();
        s.setNextRunAt(null);
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.of(s));

        Optional<JobScheduleResponse> response = jobScheduleService.pause(scheduleId);

        assertThat(response).isPresent();
        assertThat(response.get().enabled()).isFalse();
        verify(jobScheduleRepository, never()).save(any());
    }

    @Test
    void pause_returnsEmpty_whenScheduleMissing() {
        UUID scheduleId = UUID.randomUUID();
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.empty());

        assertThat(jobScheduleService.pause(scheduleId)).isEmpty();
        verify(jobScheduleRepository, never()).save(any());
    }

    @Test
    void resume_enablesSchedule_andComputesNextRunAt() {
        UUID scheduleId = UUID.randomUUID();
        JobSchedule s = enabledSchedule(scheduleId);
        s.disable();
        s.setNextRunAt(null);
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.of(s));
        when(jobScheduleRepository.save(s)).thenReturn(s);

        OffsetDateTime before = OffsetDateTime.now();
        Optional<JobScheduleResponse> response = jobScheduleService.resume(scheduleId);

        assertThat(response).isPresent();
        assertThat(response.get().enabled()).isTrue();
        assertThat(response.get().nextRunAt()).isNotNull();
        assertThat(response.get().nextRunAt()).isAfter(before);
    }

    @Test
    void resume_isIdempotent_whenAlreadyEnabled() {
        UUID scheduleId = UUID.randomUUID();
        JobSchedule s = enabledSchedule(scheduleId);
        OffsetDateTime originalNextRun = OffsetDateTime.now().plusHours(5);
        s.setNextRunAt(originalNextRun);
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.of(s));

        Optional<JobScheduleResponse> response = jobScheduleService.resume(scheduleId);

        assertThat(response).isPresent();
        assertThat(response.get().enabled()).isTrue();
        assertThat(response.get().nextRunAt()).isEqualTo(originalNextRun);
        verify(jobScheduleRepository, never()).save(any());
    }

    @Test
    void resume_returnsEmpty_whenScheduleMissing() {
        UUID scheduleId = UUID.randomUUID();
        when(jobScheduleRepository.findByIdWithOwner(scheduleId)).thenReturn(Optional.empty());

        assertThat(jobScheduleService.resume(scheduleId)).isEmpty();
        verify(jobScheduleRepository, never()).save(any());
    }

    private JobSchedule enabledSchedule(UUID id) {
        JobSchedule s = new JobSchedule(
                id, owner, "Test", JobType.CRM_SYNC, "{}",
                JobPriority.MEDIUM, 3, "0 0 * * * *", "UTC");
        s.setCreatedAt(OffsetDateTime.now());
        s.setUpdatedAt(OffsetDateTime.now());
        return s;
    }

    @Test
    void search_passesFiltersThrough_andMapsResults() {
        Pageable pageable = PageRequest.of(0, 10);
        JobSchedule schedule = new JobSchedule(
                UUID.randomUUID(), owner, "Daily Sync", JobType.CRM_SYNC, "{}",
                JobPriority.MEDIUM, 3, "0 0 * * * *", "UTC");
        schedule.setCreatedAt(OffsetDateTime.now());
        schedule.setUpdatedAt(OffsetDateTime.now());
        Page<JobSchedule> page = new PageImpl<>(List.of(schedule), pageable, 1);

        when(jobScheduleRepository.search(true, JobType.CRM_SYNC, null, null, pageable))
                .thenReturn(page);

        Page<JobScheduleResponse> result = jobScheduleService.search(
                true, JobType.CRM_SYNC, null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Daily Sync");
        assertThat(result.getContent().get(0).ownerEmail()).isEqualTo("owner@example.com");
    }

    @Test
    void create_throws_whenAuthenticatedUserNotFound() {
        UUID missing = UUID.randomUUID();
        when(userRepository.findById(missing)).thenReturn(Optional.empty());

        JobScheduleCreateRequest request = new JobScheduleCreateRequest(
                "X", JobType.CRM_SYNC, "{}", null, null, "0 0 * * * *", "UTC");

        assertThatThrownBy(() -> jobScheduleService.create(missing, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.toString());
        verify(jobScheduleRepository, never()).saveAndFlush(any());
    }
}
