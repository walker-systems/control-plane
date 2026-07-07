package dev.jwalker.controlplane.api.jobs.web;

import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.jobs.service.JobExecutionService;
import dev.jwalker.controlplane.api.jobs.web.dto.JobExecutionResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/jobs/{jobId}/executions")
@RequiredArgsConstructor
public class JobExecutionController {

    private final JobExecutionService jobExecutionService;

    // Returns every execution row for the job in a single response ordered
    // by attempt number ascending. Executions per job are bounded by the
    // retry policy (default max_retries=3, so ~4 rows), so pagination
    // would be overhead without benefit.
    @GetMapping
    public List<JobExecutionResponse> list(
            @PathVariable UUID jobId, @AuthenticationPrincipal Jwt jwt) {
        return jobExecutionService.findAllForJob(jobId, AuthenticatedCaller.from(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    @GetMapping("/{attemptNumber}")
    public JobExecutionResponse getByAttempt(
            @PathVariable UUID jobId,
            @PathVariable int attemptNumber,
            @AuthenticationPrincipal Jwt jwt) {
        return jobExecutionService.findByAttempt(jobId, attemptNumber, AuthenticatedCaller.from(jwt))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Execution not found"));
    }
}
