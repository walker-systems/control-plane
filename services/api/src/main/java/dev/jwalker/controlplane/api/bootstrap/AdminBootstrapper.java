package dev.jwalker.controlplane.api.bootstrap;

import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.RoleRepository;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Seeds well-known users on startup, idempotently: the admin account
// (required for a usable fresh install) and, optionally, a shared
// demo account. The demo user gets OPERATOR — it can see every job
// and read audit trails, which is the view the public demo wants to
// show off, without ADMIN's implication of full control.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BootstrapProperties.class)
@RequiredArgsConstructor
public class AdminBootstrapper implements ApplicationRunner {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String OPERATOR_ROLE = "OPERATOR";

    private final BootstrapProperties props;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isBlank(props.adminEmail()) || isBlank(props.adminPassword())) {
            log.warn("Bootstrap enabled but admin-email or admin-password is blank; skipping admin");
        } else {
            ensureUser(props.adminEmail(), props.adminPassword(), ADMIN_ROLE, "admin");
        }

        // Demo user is opt-in — both properties must be set.
        if (!isBlank(props.demoEmail()) && !isBlank(props.demoPassword())) {
            ensureUser(props.demoEmail(), props.demoPassword(), OPERATOR_ROLE, "demo");
        }
    }

    private void ensureUser(String email, String password, String roleName, String label) {
        if (userRepository.existsByEmail(email)) {
            log.info("Bootstrap {} user {} already exists; skipping", label, email);
            return;
        }

        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException(
                        "Role " + roleName + " not found; was V1 migration applied?"));

        User user = new User(
                null,
                email,
                passwordEncoder.encode(password),
                UserStatus.ACTIVE);
        user.addRole(role);
        userRepository.save(user);

        log.info("Bootstrap created {} user {} with role {}", label, email, roleName);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
