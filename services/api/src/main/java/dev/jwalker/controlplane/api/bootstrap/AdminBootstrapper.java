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

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BootstrapProperties.class)
@RequiredArgsConstructor
public class AdminBootstrapper implements ApplicationRunner {

    private static final String ADMIN_ROLE = "ADMIN";

    private final BootstrapProperties props;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isBlank(props.adminEmail()) || isBlank(props.adminPassword())) {
            log.warn("Bootstrap enabled but admin-email or admin-password is blank; skipping");
            return;
        }

        if (userRepository.existsByEmail(props.adminEmail())) {
            log.info("Bootstrap admin user {} already exists; skipping", props.adminEmail());
            return;
        }

        Role adminRole = roleRepository.findByName(ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Role " + ADMIN_ROLE + " not found; was V1 migration applied?"));

        User admin = new User(
                null,
                props.adminEmail(),
                passwordEncoder.encode(props.adminPassword()),
                UserStatus.ACTIVE);
        admin.addRole(adminRole);
        userRepository.save(admin);

        log.info("Bootstrap created admin user {}", props.adminEmail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
