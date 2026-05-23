package dev.jwalker.controlplane.api.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.RoleRepository;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapperTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void run_createsAdminUser_whenNoneExists() {
        BootstrapProperties props = new BootstrapProperties(true, "admin@example.com", "secret-pw");
        Role adminRole = new Role(UUID.randomUUID(), "ADMIN");

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode("secret-pw")).thenReturn("hashed");

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getRoles()).containsExactly(adminRole);
    }

    @Test
    void run_skips_whenAdminAlreadyExists() {
        BootstrapProperties props = new BootstrapProperties(true, "admin@example.com", "secret-pw");
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).save(any());
        verify(roleRepository, never()).findByName(any());
    }

    @Test
    void run_skips_whenEmailBlank() {
        BootstrapProperties props = new BootstrapProperties(true, "", "secret-pw");

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void run_skips_whenPasswordBlank() {
        BootstrapProperties props = new BootstrapProperties(true, "admin@example.com", "  ");

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void run_throws_whenAdminRoleMissing() {
        BootstrapProperties props = new BootstrapProperties(true, "admin@example.com", "secret-pw");
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);

        assertThatThrownBy(() -> bootstrapper.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN");
        verify(userRepository, never()).save(any());
    }
}
