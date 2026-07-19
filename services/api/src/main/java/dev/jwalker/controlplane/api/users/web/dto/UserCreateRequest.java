package dev.jwalker.controlplane.api.users.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record UserCreateRequest(
        @NotBlank @Email @Size(max = 255) String email,
        // Minimum length is the only server-side password rule —
        // composition rules (digits/symbols) push users toward
        // predictable patterns; length is what actually costs
        // attackers (NIST SP 800-63B's position too).
        @NotBlank @Size(min = 12, max = 255) String password,
        // Null/empty → USER. Validated against the roles table, not an
        // enum here, so the DB stays the source of truth.
        Set<String> roles
) {
}
