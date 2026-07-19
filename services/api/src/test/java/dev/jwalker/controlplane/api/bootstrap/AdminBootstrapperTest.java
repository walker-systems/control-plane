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

    private static BootstrapProperties adminOnly(String email, String password) {
        return new BootstrapProperties(true, email, password, "", "", "", "");
    }

    @Test
    void run_createsAdminUser_whenNoneExists() {
        BootstrapProperties props = adminOnly("admin@example.com", "secret-pw");
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
        BootstrapProperties props = adminOnly("admin@example.com", "secret-pw");
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).save(any());
        verify(roleRepository, never()).findByName(any());
    }

    @Test
    void run_skips_whenEmailBlank() {
        BootstrapProperties props = adminOnly("", "secret-pw");

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void run_skips_whenPasswordBlank() {
        BootstrapProperties props = adminOnly("admin@example.com", "  ");

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void run_throws_whenAdminRoleMissing() {
        BootstrapProperties props = adminOnly("admin@example.com", "secret-pw");
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);

        assertThatThrownBy(() -> bootstrapper.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN");
        verify(userRepository, never()).save(any());
    }

    @Test
    void run_createsDemoUserWithOperatorRole_whenDemoPropsSet() {
        BootstrapProperties props = new BootstrapProperties(
                true, "admin@example.com", "secret-pw", "demo@example.com", "demo-pw", "", "");
        Role adminRole = new Role(UUID.randomUUID(), "ADMIN");
        Role operatorRole = new Role(UUID.randomUUID(), "OPERATOR");

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);
        when(userRepository.existsByEmail("demo@example.com")).thenReturn(false);
        when(roleRepository.findByName("OPERATOR")).thenReturn(Optional.of(operatorRole));
        when(passwordEncoder.encode("demo-pw")).thenReturn("demo-hashed");

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("demo@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("demo-hashed");
        assertThat(saved.getRoles()).containsExactly(operatorRole);
        assertThat(saved.getRoles()).doesNotContain(adminRole);
    }

    @Test
    void run_skipsDemoUser_whenAlreadyExists() {
        BootstrapProperties props = new BootstrapProperties(
                true, "admin@example.com", "secret-pw", "demo@example.com", "demo-pw", "", "");

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);
        when(userRepository.existsByEmail("demo@example.com")).thenReturn(true);

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).save(any());
        verify(roleRepository, never()).findByName(any());
    }

    @Test
    void run_createsRestrictedPersonaWithUserRole_whenPrimaryDemoAlsoSet() {
        BootstrapProperties props = new BootstrapProperties(
                true, "admin@example.com", "secret-pw",
                "demo@example.com", "demo-pw", "viewer@example.com", "viewer-pw");
        Role userRole = new Role(UUID.randomUUID(), "USER");

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);
        when(userRepository.existsByEmail("demo@example.com")).thenReturn(true);
        when(userRepository.existsByEmail("viewer@example.com")).thenReturn(false);
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("viewer-pw")).thenReturn("viewer-hashed");

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("viewer@example.com");
        assertThat(captor.getValue().getRoles()).containsExactly(userRole);
    }

    @Test
    void run_skipsRestrictedPersona_whenPrimaryDemoDisabled() {
        // The documented disable switch empties only the primary demo
        // pair — the viewer must go down with it, not survive on its
        // compose defaults.
        BootstrapProperties props = new BootstrapProperties(
                true, "admin@example.com", "secret-pw", "", "", "viewer@example.com", "viewer-pw");

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).existsByEmail("viewer@example.com");
        verify(userRepository, never()).save(any());
    }

    @Test
    void run_skipsDemoUser_whenOnlyEmailSet() {
        BootstrapProperties props = new BootstrapProperties(
                true, "admin@example.com", "secret-pw", "demo@example.com", "", "", "");

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);

        AdminBootstrapper bootstrapper = new AdminBootstrapper(props, userRepository, roleRepository, passwordEncoder);
        bootstrapper.run(null);

        verify(userRepository, never()).existsByEmail("demo@example.com");
        verify(userRepository, never()).save(any());
    }
}
