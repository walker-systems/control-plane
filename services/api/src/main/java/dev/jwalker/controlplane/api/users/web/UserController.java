package dev.jwalker.controlplane.api.users.web;

import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import dev.jwalker.controlplane.api.users.service.UserAdminException;
import dev.jwalker.controlplane.api.users.service.UserAdminService;
import dev.jwalker.controlplane.api.users.web.dto.UserCreateRequest;
import dev.jwalker.controlplane.api.users.web.dto.UserResponse;
import dev.jwalker.controlplane.api.users.web.dto.UserUpdateRequest;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final UserAdminService userAdminService;

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return userRepository.findByIdWithRoles(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // Everything below is ADMIN-only, enforced in UserAdminService
    // (same service-layer pattern as audit reads) — non-admins get 403
    // regardless of how the endpoint is reached.

    @GetMapping
    public Page<UserResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        return userAdminService.list(pageable, AuthenticatedCaller.from(jwt));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UserResponse response = userAdminService.create(request, AuthenticatedCaller.from(jwt));
        return ResponseEntity
                .created(URI.create("/api/users/" + response.id()))
                .body(response);
    }

    @PatchMapping("/{id}")
    public UserResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UserUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return userAdminService.update(id, request, AuthenticatedCaller.from(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @ExceptionHandler(UserAdminException.class)
    ProblemDetail handleUserAdmin(UserAdminException e) {
        HttpStatus status = switch (e.reason()) {
            case UNKNOWN_ROLE -> HttpStatus.BAD_REQUEST;
            case DUPLICATE_EMAIL, SELF_MODIFICATION -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }
}
