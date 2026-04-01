package dev.jwalker.controlplane.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefreshTokenRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedUser(String email) {
        return userRepository.saveAndFlush(new User(null, email, "hash", UserStatus.ACTIVE));
    }

    @Test
    void findByTokenHash_returnsSavedToken() {
        User user = savedUser("user@example.com");
        OffsetDateTime expires = OffsetDateTime.now().plusDays(7);
        RefreshToken token = new RefreshToken(null, user, "abc123hash", expires);

        RefreshToken saved = refreshTokenRepository.saveAndFlush(token);

        Optional<RefreshToken> result = refreshTokenRepository.findByTokenHash("abc123hash");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getTokenHash()).isEqualTo("abc123hash");
    }

    @Test
    void findByTokenHash_returnsEmpty_whenNotFound() {
        Optional<RefreshToken> result = refreshTokenRepository.findByTokenHash("nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void findByUser_returnsTokensForUser() {
        User user = savedUser("user@example.com");
        OffsetDateTime expires = OffsetDateTime.now().plusDays(7);
        refreshTokenRepository.saveAndFlush(new RefreshToken(null, user, "token-a", expires));
        refreshTokenRepository.saveAndFlush(new RefreshToken(null, user, "token-b", expires));

        List<RefreshToken> result = refreshTokenRepository.findByUser(user);

        assertThat(result).hasSize(2)
                .extracting(RefreshToken::getTokenHash)
                .containsExactlyInAnyOrder("token-a", "token-b");
    }

    @Test
    void findByUser_returnsEmpty_whenUserHasNoTokens() {
        User user = savedUser("empty@example.com");

        List<RefreshToken> result = refreshTokenRepository.findByUser(user);

        assertThat(result).isEmpty();
    }

    @Test
    void deleteByUser_removesAllTokensForUser() {
        User user = savedUser("user@example.com");
        OffsetDateTime expires = OffsetDateTime.now().plusDays(7);
        refreshTokenRepository.saveAndFlush(new RefreshToken(null, user, "token-a", expires));
        refreshTokenRepository.saveAndFlush(new RefreshToken(null, user, "token-b", expires));

        refreshTokenRepository.deleteByUser(user);
        refreshTokenRepository.flush();

        List<RefreshToken> result = refreshTokenRepository.findByUser(user);

        assertThat(result).isEmpty();
    }

    @Test
    void countActiveTokensForUser_countsOnlyNonRevokedNonExpiredTokens() {
        User user = savedUser("user@example.com");
        OffsetDateTime now = OffsetDateTime.now();

        RefreshToken active = new RefreshToken(null, user, "active-token", now.plusDays(7));
        refreshTokenRepository.saveAndFlush(active);

        RefreshToken revoked = new RefreshToken(null, user, "revoked-token", now.plusDays(7));
        revoked.setRevokedAt(now.minusHours(1));
        refreshTokenRepository.saveAndFlush(revoked);

        RefreshToken expired = new RefreshToken(null, user, "expired-token", now.minusDays(1));
        refreshTokenRepository.saveAndFlush(expired);

        long count = refreshTokenRepository.countActiveTokensForUser(user, now);

        assertThat(count).isEqualTo(1);
    }
}
