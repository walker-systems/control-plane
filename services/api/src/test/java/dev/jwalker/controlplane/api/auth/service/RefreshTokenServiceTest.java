package dev.jwalker.controlplane.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.auth.config.SecurityProperties;
import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.auth.repository.RefreshTokenRepository;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefreshTokenServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private final SecurityProperties props = new SecurityProperties("unused-in-this-test", 15, 7);
    private RefreshTokenService service;

    private User saveUser() {
        User user = new User(null, "user-" + UUID.randomUUID() + "@example.com", "hash", UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private RefreshTokenService service() {
        if (service == null) {
            service = new RefreshTokenService(refreshTokenRepository, props);
        }
        return service;
    }

    @Test
    void issue_returnsRawTokenAndStoresOnlyHash() {
        User user = saveUser();

        RefreshTokenService.IssuedRefreshToken issued = service().issue(user);

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(OffsetDateTime.now().plusDays(6));

        var stored = refreshTokenRepository.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTokenHash())
                .isNotEqualTo(issued.rawToken())
                .hasSize(64);
    }

    @Test
    void findActive_returnsToken_forMatchingRawToken() {
        User user = saveUser();
        RefreshTokenService.IssuedRefreshToken issued = service().issue(user);

        Optional<RefreshToken> found = service().findActive(issued.rawToken());

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    void findActive_returnsEmpty_forUnknownToken() {
        assertThat(service().findActive("not-a-real-token")).isEmpty();
    }

    @Test
    void findActive_returnsEmpty_forRevokedToken() {
        User user = saveUser();
        RefreshTokenService.IssuedRefreshToken issued = service().issue(user);
        RefreshToken stored = refreshTokenRepository.findAll().get(0);

        service().revoke(stored);

        assertThat(service().findActive(issued.rawToken())).isEmpty();
    }

    @Test
    void revokeIfPresent_marksTokenRevoked() {
        User user = saveUser();
        RefreshTokenService.IssuedRefreshToken issued = service().issue(user);

        service().revokeIfPresent(issued.rawToken());

        RefreshToken stored = refreshTokenRepository.findAll().get(0);
        assertThat(stored.isRevoked()).isTrue();
    }

    @Test
    void revokeIfPresent_silentlyIgnoresUnknownToken() {
        service().revokeIfPresent("nonexistent-token");
        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }
}
