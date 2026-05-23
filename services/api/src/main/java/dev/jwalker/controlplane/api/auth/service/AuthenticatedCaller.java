package dev.jwalker.controlplane.api.auth.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public record AuthenticatedCaller(UUID userId, Set<String> roles) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_OPERATOR = "OPERATOR";

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isPrivileged() {
        return hasRole(ROLE_ADMIN) || hasRole(ROLE_OPERATOR);
    }

    public static AuthenticatedCaller from(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedCaller(
                userId,
                roles == null ? Set.of() : Set.copyOf(roles));
    }
}
