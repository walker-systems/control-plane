package dev.jwalker.controlplane.api.audit.service;

import dev.jwalker.controlplane.api.audit.model.AuditEvent;
import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.repository.AuditEventRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // Auto-actor: reads from SecurityContext. For LOGIN_FAILED and any other
    // event where the caller isn't authenticated, use recordWithActor(null, ...).
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent record(
            AuditEventType eventType,
            String targetType,
            UUID targetId,
            Map<String, Object> metadata) {
        return recordWithActor(eventType, currentActorIdOrNull(), targetType, targetId, metadata);
    }

    // Explicit actor: for auth flows (login/refresh) where the user isn't in
    // SecurityContext yet, or system actions where we don't want to inherit
    // the current request's actor.
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent recordWithActor(
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

    private static String currentIpAddressOrNull() {
        HttpServletRequest req = currentRequestOrNull();
        if (req == null) {
            return null;
        }
        // Prefer X-Forwarded-For when behind a proxy; fall back to direct address.
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
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
