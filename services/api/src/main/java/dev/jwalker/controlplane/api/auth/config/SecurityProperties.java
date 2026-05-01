package dev.jwalker.controlplane.api.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        String jwtSecret,
        int accessTokenMinutes,
        int refreshTokenDays
) {
}
