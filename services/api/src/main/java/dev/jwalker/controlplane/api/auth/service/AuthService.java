package dev.jwalker.controlplane.api.auth.service;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.auth.web.dto.TokenResponse;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditEventService auditEventService;

    @Transactional
    public TokenResponse login(String email, String password) {
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new AuthException(
                            AuthException.Reason.INVALID_CREDENTIALS, "Invalid email or password"));

            if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS, "Invalid email or password");
            }

            guardStatus(user);

            user.setLastLoginAt(OffsetDateTime.now());

            AppUserDetails principal = new AppUserDetails(user);
            JwtService.IssuedAccessToken access = jwtService.issueAccessToken(principal);
            RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(user);

            auditEventService.recordWithActor(
                    AuditEventType.LOGIN_SUCCEEDED, user.getId(), null, null, Map.of());

            return TokenResponse.bearer(access.token(), refresh.rawToken(), access.ttl().toSeconds());
        } catch (AuthException e) {
            // REQUIRES_NEW so the audit row survives the rollback that the
            // AuthException will trigger on the way out.
            auditEventService.recordIndependently(
                    AuditEventType.LOGIN_FAILED,
                    null,
                    null,
                    null,
                    Map.of("email", email, "reason", e.reason().name()));
            throw e;
        }
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken existing = refreshTokenService.findActive(rawRefreshToken)
                .orElseThrow(() -> new AuthException(
                        AuthException.Reason.INVALID_REFRESH_TOKEN, "Invalid or expired refresh token"));

        User user = existing.getUser();
        guardStatus(user);

        refreshTokenService.revoke(existing);

        AppUserDetails principal = new AppUserDetails(user);
        JwtService.IssuedAccessToken access = jwtService.issueAccessToken(principal);
        RefreshTokenService.IssuedRefreshToken rotated = refreshTokenService.issue(user);

        auditEventService.recordWithActor(
                AuditEventType.TOKEN_REFRESHED, user.getId(), null, null, Map.of());

        return TokenResponse.bearer(access.token(), rotated.rawToken(), access.ttl().toSeconds());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        // Only audit when we actually revoked something; ignore bogus tokens
        // silently rather than logging noise.
        refreshTokenService.revokeIfPresent(rawRefreshToken)
                .ifPresent(token -> auditEventService.recordWithActor(
                        AuditEventType.LOGOUT, token.getUser().getId(), null, null, Map.of()));
    }

    private static void guardStatus(User user) {
        switch (user.getStatus()) {
            case ACTIVE -> {
            }
            case LOCKED -> throw new AuthException(
                    AuthException.Reason.ACCOUNT_LOCKED, "Account is locked");
            case DISABLED -> throw new AuthException(
                    AuthException.Reason.ACCOUNT_DISABLED, "Account is disabled");
        }
    }
}
