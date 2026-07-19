package dev.jwalker.controlplane.api.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapProperties(
        boolean enabled,
        String adminEmail,
        String adminPassword,
        // Optional shared demo account (OPERATOR role) for the public
        // demo's one-click login. Blank = not created.
        String demoEmail,
        String demoPassword,
        // Optional second demo persona with the plain USER role, so
        // visitors can experience the restricted view (own jobs only,
        // no audit). Blank = not created.
        String demoUserEmail,
        String demoUserPassword
) {
}
