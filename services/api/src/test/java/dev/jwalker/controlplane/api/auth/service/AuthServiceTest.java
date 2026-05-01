package dev.jwalker.controlplane.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.auth.web.dto.TokenResponse;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User(UUID.randomUUID(), "user@example.com", "hashed", UserStatus.ACTIVE);
    }

    @Test
    void login_withValidCredentials_returnsTokens_andUpdatesLastLogin() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);
        when(jwtService.issueAccessToken(any())).thenReturn(
                new JwtService.IssuedAccessToken("access-token", Instant.now().plusSeconds(900), Duration.ofMinutes(15)));
        when(refreshTokenService.issue(user)).thenReturn(
                new RefreshTokenService.IssuedRefreshToken("raw-refresh", OffsetDateTime.now().plusDays(7)));

        TokenResponse response = authService.login("user@example.com", "password");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    void login_withWrongPassword_throwsInvalidCredentials() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("user@example.com", "wrong"))
                .isInstanceOf(AuthException.class)
                .extracting("reason").isEqualTo(AuthException.Reason.INVALID_CREDENTIALS);

        verify(jwtService, never()).issueAccessToken(any());
        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void login_withUnknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost@example.com", "anything"))
                .isInstanceOf(AuthException.class)
                .extracting("reason").isEqualTo(AuthException.Reason.INVALID_CREDENTIALS);
    }

    @Test
    void login_withLockedAccount_throwsAccountLocked() {
        user.setStatus(UserStatus.LOCKED);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("user@example.com", "password"))
                .isInstanceOf(AuthException.class)
                .extracting("reason").isEqualTo(AuthException.Reason.ACCOUNT_LOCKED);
    }

    @Test
    void login_withDisabledAccount_throwsAccountDisabled() {
        user.setStatus(UserStatus.DISABLED);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("user@example.com", "password"))
                .isInstanceOf(AuthException.class)
                .extracting("reason").isEqualTo(AuthException.Reason.ACCOUNT_DISABLED);
    }

    @Test
    void refresh_rotatesTokens_andRevokesOld() {
        RefreshToken existing = new RefreshToken(UUID.randomUUID(), user, "hash", OffsetDateTime.now().plusDays(7));
        when(refreshTokenService.findActive("old-raw")).thenReturn(Optional.of(existing));
        when(jwtService.issueAccessToken(any())).thenReturn(
                new JwtService.IssuedAccessToken("new-access", Instant.now().plusSeconds(900), Duration.ofMinutes(15)));
        when(refreshTokenService.issue(user)).thenReturn(
                new RefreshTokenService.IssuedRefreshToken("new-raw", OffsetDateTime.now().plusDays(7)));

        TokenResponse response = authService.refresh("old-raw");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-raw");
        verify(refreshTokenService, times(1)).revoke(existing);
    }

    @Test
    void refresh_withInvalidToken_throwsInvalidRefreshToken() {
        when(refreshTokenService.findActive("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("bad"))
                .isInstanceOf(AuthException.class)
                .extracting("reason").isEqualTo(AuthException.Reason.INVALID_REFRESH_TOKEN);
    }

    @Test
    void refresh_withDisabledUser_throwsAccountDisabled_andDoesNotRotate() {
        user.setStatus(UserStatus.DISABLED);
        RefreshToken existing = new RefreshToken(UUID.randomUUID(), user, "hash", OffsetDateTime.now().plusDays(7));
        when(refreshTokenService.findActive("old-raw")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.refresh("old-raw"))
                .isInstanceOf(AuthException.class)
                .extracting("reason").isEqualTo(AuthException.Reason.ACCOUNT_DISABLED);

        verify(refreshTokenService, never()).revoke(any());
        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void logout_delegatesToRevokeIfPresent() {
        authService.logout("any-token");
        verify(refreshTokenService).revokeIfPresent("any-token");
    }
}
