package dev.jwalker.controlplane.api.audit.web;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.audit.web.dto.AuditEventResponse;
import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventService auditEventService;

    @GetMapping
    public Page<AuditEventResponse> list(
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID targetId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return auditEventService.search(
                eventType, actorUserId, targetType, targetId, pageable, AuthenticatedCaller.from(jwt));
    }

    @GetMapping("/target/{type}/{id}")
    public Page<AuditEventResponse> listForTarget(
            @PathVariable String type,
            @PathVariable UUID id,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return auditEventService.search(
                null, null, type, id, pageable, AuthenticatedCaller.from(jwt));
    }
}
