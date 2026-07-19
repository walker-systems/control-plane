package dev.jwalker.controlplane.api.users.web.dto;

import dev.jwalker.controlplane.api.users.model.UserStatus;
import jakarta.validation.constraints.Size;
import java.util.Set;

// PATCH contract: null means "don't touch", matching
// JobScheduleUpdateRequest. @Size passes null through, so an omitted
// roles field doesn't trip validation — but a present-and-empty set
// does (a user with zero roles could log in and do nothing, which is
// never what an admin meant).
public record UserUpdateRequest(
        UserStatus status,
        @Size(min = 1) Set<String> roles
) {
}
