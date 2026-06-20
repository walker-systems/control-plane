package dev.jwalker.controlplane.api.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
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
}
