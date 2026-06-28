package dev.jwalker.controlplane.api.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.audit.web.dto.AuditEventResponse;
import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @InjectMocks
    private AuditEventService auditEventService;

    @BeforeEach
    void wireObjectMapper() {
        // ObjectMapper is final so InjectMocks can't substitute it; rebuild manually.
        auditEventService = new AuditEventService(auditEventRepository, userRepository, objectMapper);
    }

    @AfterEach
    void cleanupContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordWithActor_persistsEntityWithGivenFields() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User actorRef = new User(actorId, "u@example.com", "h", UserStatus.ACTIVE);
        when(userRepository.getReferenceById(actorId)).thenReturn(actorRef);
        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        auditEventService.recordWithActor(
                AuditEventType.JOB_CREATED,
                actorId,
                "Job",
                targetId,
                Map.of("k", "v"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(AuditEventType.JOB_CREATED);
        assertThat(saved.getActor()).isEqualTo(actorRef);
        assertThat(saved.getTargetType()).isEqualTo("Job");
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(saved.getMetadataJson()).contains("\"k\":\"v\"");
    }

    @Test
    void recordWithActor_allowsNullActor() {
        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        auditEventService.recordWithActor(
                AuditEventType.LOGIN_FAILED,
                null,
                null,
                null,
                Map.of("email", "x@example.com"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getActor()).isNull();
        assertThat(saved.getTargetType()).isNull();
        assertThat(saved.getTargetId()).isNull();
        assertThat(saved.getMetadataJson()).contains("x@example.com");
    }

    @Test
    void record_picksActorFromSecurityContext() {
        UUID actorId = UUID.randomUUID();
        User actorRef = new User(actorId, "u@example.com", "h", UserStatus.ACTIVE);
        when(userRepository.getReferenceById(actorId)).thenReturn(actorRef);
        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(actorId.toString())
                .claim("roles", java.util.List.of("USER"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, "n/a", java.util.List.of()));

        auditEventService.record(
                AuditEventType.JOB_CREATED,
                "Job",
                UUID.randomUUID(),
                Map.of());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo(actorRef);
    }

    @Test
    void record_actorIsNullWhenSecurityContextEmpty() {
        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        auditEventService.record(
                AuditEventType.LOGIN_FAILED,
                null,
                null,
                Map.of());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isNull();
    }

    @Test
    void recordWithActor_capturesIpAndUserAgentFromCurrentRequest() {
        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setRemoteAddr("203.0.113.42");
        mockReq.addHeader("User-Agent", "Mozilla/5.0 (test)");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockReq));

        auditEventService.recordWithActor(
                AuditEventType.LOGIN_FAILED, null, null, null, Map.of());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.42");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0 (test)");
    }

    @Test
    void recordWithActor_prefersXForwardedForHeader() {
        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setRemoteAddr("10.0.0.1");
        mockReq.addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockReq));

        auditEventService.recordWithActor(
                AuditEventType.LOGIN_FAILED, null, null, null, Map.of());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.42");
    }

    @Test
    void recordWithActor_ipAndUserAgentNullOutsideRequest() {
        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        auditEventService.recordWithActor(
                AuditEventType.LOGIN_FAILED, null, null, null, Map.of());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getIpAddress()).isNull();
        assertThat(saved.getUserAgent()).isNull();
    }

    @Test
    void search_passesFiltersThrough_andMapsEntitiesToResponses() {
        Pageable pageable = PageRequest.of(0, 10);
        UUID actorId = UUID.randomUUID();
        User actor = new User(actorId, "alice@example.com", "h", UserStatus.ACTIVE);
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(), actor, AuditEventType.JOB_CREATED,
                "Job", UUID.randomUUID(), "{}", "10.0.0.1", "curl/8.0");
        event.setCreatedAt(OffsetDateTime.now());
        Page<AuditEvent> page = new PageImpl<>(List.of(event), pageable, 1);

        when(auditEventRepository.search(
                eq(AuditEventType.JOB_CREATED), eq(actorId),
                isNull(), isNull(), eq(pageable)))
                .thenReturn(page);

        AuthenticatedCaller operatorCaller =
                new AuthenticatedCaller(UUID.randomUUID(), Set.of("OPERATOR"));
        Page<AuditEventResponse> result = auditEventService.search(
                AuditEventType.JOB_CREATED, actorId, null, null, pageable, operatorCaller);

        assertThat(result.getTotalElements()).isEqualTo(1);
        AuditEventResponse response = result.getContent().get(0);
        assertThat(response.eventType()).isEqualTo(AuditEventType.JOB_CREATED);
        assertThat(response.actorUserId()).isEqualTo(actorId);
        assertThat(response.actorEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void search_throwsAccessDenied_forUserCaller() {
        AuthenticatedCaller userCaller =
                new AuthenticatedCaller(UUID.randomUUID(), Set.of("USER"));

        assertThatThrownBy(() -> auditEventService.search(
                null, null, null, null, Pageable.unpaged(), userCaller))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("OPERATOR or ADMIN");

        org.mockito.Mockito.verify(auditEventRepository, never())
                .search(any(), any(), any(), any(), any());
    }

    @Test
    void search_succeeds_forAdminCaller() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<AuditEvent> empty = new PageImpl<>(List.of(), pageable, 0);
        when(auditEventRepository.search(isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(empty);

        AuthenticatedCaller adminCaller =
                new AuthenticatedCaller(UUID.randomUUID(), Set.of("ADMIN"));

        assertThat(auditEventService.search(null, null, null, null, pageable, adminCaller)
                .getTotalElements()).isZero();
    }
}
