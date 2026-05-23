package dev.jwalker.controlplane.api.schedules.web;

import dev.jwalker.controlplane.api.schedules.service.InvalidScheduleConfigException;
import dev.jwalker.controlplane.api.schedules.service.JobScheduleService;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleCreateRequest;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class JobScheduleController {

    private final JobScheduleService jobScheduleService;

    @PostMapping
    public ResponseEntity<JobScheduleResponse> create(
            @Valid @RequestBody JobScheduleCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        JobScheduleResponse response = jobScheduleService.create(ownerId, request);
        return ResponseEntity
                .created(URI.create("/api/schedules/" + response.id()))
                .body(response);
    }

    @ExceptionHandler(InvalidScheduleConfigException.class)
    ProblemDetail handleScheduleConfig(InvalidScheduleConfigException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID_CRON, INVALID_TIMEZONE -> HttpStatus.BAD_REQUEST;
            case DUPLICATE_NAME -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }
}
