package dev.jwalker.controlplane.api.jobs.web;

import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.jobs.model.JobPriority;
import dev.jwalker.controlplane.api.jobs.model.JobStatus;
import dev.jwalker.controlplane.api.jobs.model.JobType;
import dev.jwalker.controlplane.api.jobs.service.JobService;
import dev.jwalker.controlplane.api.jobs.service.JobStateException;
import dev.jwalker.controlplane.api.jobs.web.dto.JobCreateRequest;
import dev.jwalker.controlplane.api.jobs.web.dto.JobResponse;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping
    public ResponseEntity<JobResponse> create(
            @Valid @RequestBody JobCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        JobResponse response = jobService.create(ownerId, request);
        return ResponseEntity
                .created(URI.create("/api/jobs/" + response.id()))
                .body(response);
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return jobService.findById(id, AuthenticatedCaller.from(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    @GetMapping
    public Page<JobResponse> list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) JobType type,
            @RequestParam(required = false) JobPriority priority,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) UUID sourceScheduleId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return jobService.search(
                status, type, priority, ownerId, sourceScheduleId, pageable, AuthenticatedCaller.from(jwt));
    }

    @PostMapping("/{id}/cancel")
    public JobResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return jobService.cancel(id, AuthenticatedCaller.from(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    @PostMapping("/{id}/retry")
    public JobResponse retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return jobService.retry(id, AuthenticatedCaller.from(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    @ExceptionHandler(JobStateException.class)
    ProblemDetail handleJobState(JobStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }
}
