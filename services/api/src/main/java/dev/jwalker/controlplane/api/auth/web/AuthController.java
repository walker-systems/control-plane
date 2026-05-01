package dev.jwalker.controlplane.api.auth.web;

import dev.jwalker.controlplane.api.auth.service.AuthException;
import dev.jwalker.controlplane.api.auth.service.AuthService;
import dev.jwalker.controlplane.api.auth.web.dto.LoginRequest;
import dev.jwalker.controlplane.api.auth.web.dto.LogoutRequest;
import dev.jwalker.controlplane.api.auth.web.dto.RefreshRequest;
import dev.jwalker.controlplane.api.auth.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    @ExceptionHandler(AuthException.class)
    ProblemDetail handleAuth(AuthException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID_CREDENTIALS, INVALID_REFRESH_TOKEN -> HttpStatus.UNAUTHORIZED;
            case ACCOUNT_LOCKED, ACCOUNT_DISABLED -> HttpStatus.FORBIDDEN;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return problem;
    }
}
