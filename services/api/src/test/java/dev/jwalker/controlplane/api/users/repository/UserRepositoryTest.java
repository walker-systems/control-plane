package dev.jwalker.controlplane.api.users.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
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
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByEmail_returnsSavedUser() {
        User user = new User(null, "justin@example.com", "hashed-password", UserStatus.ACTIVE);

        User saved = userRepository.saveAndFlush(user);

        Optional<User> result = userRepository.findByEmail("justin@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getEmail()).isEqualTo("justin@example.com");
        assertThat(result.get().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void existsByEmail_returnsTrue_whenUserExists() {
        User user = new User(null, "exists@example.com", "hashed-password", UserStatus.ACTIVE);
        user.setEmail("exists@example.com");
        user.setPasswordHash("hashed-password");
        user.setStatus(UserStatus.ACTIVE);

        userRepository.saveAndFlush(user);

        boolean exists = userRepository.existsByEmail("exists@example.com");

        assertThat(exists).isTrue();
    }
}
