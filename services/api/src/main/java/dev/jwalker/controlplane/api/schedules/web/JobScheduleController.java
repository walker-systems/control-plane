package dev.jwalker.controlplane.api.schedules.web;

import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.schedules.service.InvalidScheduleConfigException;
import dev.jwalker.controlplane.api.schedules.service.JobScheduleService;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleCreateRequest;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleResponse;
import dev.jwalker.controlplane.api.schedules.web.dto.JobScheduleUpdateRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    @GetMapping("/{id}")
    public JobScheduleResponse get(@PathVariable UUID id) {
        return jobScheduleService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
    }

    @GetMapping
    public Page<JobScheduleResponse> list(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) JobType type,
            @RequestParam(required = false) JobPriority priority,
            @RequestParam(required = false) UUID ownerId,
            @PageableDefault(size = 20, sort = "nextRunAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return jobScheduleService.search(enabled, type, priority, ownerId, pageable);
    }

    @PostMapping("/{id}/pause")
    public JobScheduleResponse pause(@PathVariable UUID id) {
        return jobScheduleService.pause(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
    }

    @PostMapping("/{id}/resume")
    public JobScheduleResponse resume(@PathVariable UUID id) {
        return jobScheduleService.resume(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
    }

    @PutMapping("/{id}")
    public JobScheduleResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody JobScheduleUpdateRequest request) {
        return jobScheduleService.update(id, request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (!jobScheduleService.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found");
        }
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
