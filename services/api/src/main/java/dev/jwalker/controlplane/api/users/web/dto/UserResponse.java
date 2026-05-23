package dev.jwalker.controlplane.api.users.web.dto;

import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record UserResponse(
        UUID id,
        String email,
        UserStatus status,
        Set<String> roles,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt
) {
    public static UserResponse from(User user) {
        Set<String> roleNames = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toUnmodifiableSet());
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getStatus(),
                roleNames,
                user.getCreatedAt(),
                user.getLastLoginAt());
    }
}
