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
        String demoPassword
) {
}
