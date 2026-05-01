package dev.jwalker.controlplane.api.auth.service;

import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.auth.web.dto.TokenResponse;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
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

    @Transactional
    public TokenResponse login(String email, String password) {
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

        return TokenResponse.bearer(access.token(), refresh.rawToken(), access.ttl().toSeconds());
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

        return TokenResponse.bearer(access.token(), rotated.rawToken(), access.ttl().toSeconds());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeIfPresent(rawRefreshToken);
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
