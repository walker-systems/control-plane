package dev.jwalker.controlplane.api.audit.service;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.audit.web.dto.AuditEventResponse;
import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // Auto-actor: reads from SecurityContext. Audit ties to the caller's
    // transaction — if the caller rolls back, the audit row goes too.
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent record(
            AuditEventType eventType,
            String targetType,
            UUID targetId,
            Map<String, Object> metadata) {
        return doRecord(eventType, currentActorIdOrNull(), targetType, targetId, metadata);
    }

    // Explicit actor: for auth flows (login/refresh) where the user isn't in
    // SecurityContext yet, or system actions. Still ties to caller's transaction.
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordWithActor(
            AuditEventType eventType,
            UUID actorUserId,
            String targetType,
            UUID targetId,
            Map<String, Object> metadata) {
        return doRecord(eventType, actorUserId, targetType, targetId, metadata);
    }

    // REQUIRES_NEW for failure-audit cases (e.g., LOGIN_FAILED inside a catch
    // block of a @Transactional method that will rethrow). The new transaction
    // commits independently so the audit row survives the caller's rollback.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordIndependently(
            AuditEventType eventType,
            UUID actorUserId,
            String targetType,
            UUID targetId,
            Map<String, Object> metadata) {
        return doRecord(eventType, actorUserId, targetType, targetId, metadata);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> search(
            AuditEventType eventType,
            UUID actorUserId,
            String targetType,
            UUID targetId,
            Pageable pageable,
            AuthenticatedCaller caller) {
        // Audit data is sensitive — exposes who did what, when, from where.
        // Only OPERATOR and ADMIN are trusted to read it. USER role gets 403
        // via Spring Security's default access-denied handling, which
        // recognizes AccessDeniedException thrown during request processing.
        if (!caller.isPrivileged()) {
            throw new AccessDeniedException("Audit access requires OPERATOR or ADMIN role");
        }
        return auditEventRepository.search(eventType, actorUserId, targetType, targetId, pageable)
                .map(AuditEventResponse::from);
    }

    private AuditEvent doRecord(
            AuditEventType eventType,
            UUID actorUserId,
            String targetType,
            UUID targetId,
            Map<String, Object> metadata) {
        User actor = actorUserId != null ? userRepository.getReferenceById(actorUserId) : null;

        AuditEvent event = new AuditEvent(
                null,
                actor,
                eventType,
                targetType,
                targetId,
                serializeMetadata(metadata),
                currentIpAddressOrNull(),
                currentUserAgentOrNull());
        return auditEventRepository.save(event);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize audit metadata; storing null", e);
            return null;
        }
    }

    private static UUID currentActorIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    // Matches audit_events.ip_address VARCHAR(64). Audit writes join the
    // caller's transaction (REQUIRED), so a malformed/oversized forwarded
    // header must not be able to fail the insert and roll back the whole op.
    private static final int IP_MAX_LEN = 64;

    private static String currentIpAddressOrNull() {
        HttpServletRequest req = currentRequestOrNull();
        if (req == null) {
            return null;
        }
        String forwarded = req.getHeader("X-Forwarded-For");
        String raw = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : req.getRemoteAddr();
        return truncate(raw, IP_MAX_LEN);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private static String currentUserAgentOrNull() {
        HttpServletRequest req = currentRequestOrNull();
        return req != null ? req.getHeader("User-Agent") : null;
    }

    private static HttpServletRequest currentRequestOrNull() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
