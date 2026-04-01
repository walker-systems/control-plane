package dev.jwalker.controlplane.api.users.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jwalker.controlplane.api.users.model.Role;
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
class RoleRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16");

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void findByName_returnsSavedRole() {
        Role role = new Role(null, "AUDITOR");

        Role saved = roleRepository.saveAndFlush(role);

        Optional<Role> result = roleRepository.findByName("AUDITOR");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getName()).isEqualTo("AUDITOR");
    }

    @Test
    void findByName_returnsEmpty_whenNotFound() {
        Optional<Role> result = roleRepository.findByName("NONEXISTENT");

        assertThat(result).isEmpty();
    }

    @Test
    void existsByName_returnsTrue_whenRoleExists() {
        Role role = new Role(null, "VIEWER");

        roleRepository.saveAndFlush(role);

        boolean exists = roleRepository.existsByName("VIEWER");

        assertThat(exists).isTrue();
    }

    @Test
    void existsByName_returnsFalse_whenRoleDoesNotExist() {
        boolean exists = roleRepository.existsByName("NONEXISTENT");

        assertThat(exists).isFalse();
    }
}
