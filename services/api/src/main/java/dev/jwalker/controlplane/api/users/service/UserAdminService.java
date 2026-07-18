package dev.jwalker.controlplane.api.users.service;

import dev.jwalker.controlplane.api.audit.model.AuditEventType;
import dev.jwalker.controlplane.api.audit.service.AuditEventService;
import dev.jwalker.controlplane.api.auth.service.AuthenticatedCaller;
import dev.jwalker.controlplane.api.auth.service.RefreshTokenService;
import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import dev.jwalker.controlplane.api.users.repository.RoleRepository;
import dev.jwalker.controlplane.api.users.repository.UserRepository;
import dev.jwalker.controlplane.api.users.web.dto.UserCreateRequest;
import dev.jwalker.controlplane.api.users.web.dto.UserResponse;
import dev.jwalker.controlplane.api.users.web.dto.UserUpdateRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Admin-only user management. There is deliberately no self-service
// registration: accounts exist because an ADMIN created one (or
// bootstrap did on first boot). Authorization lives here in the
// service — same pattern as AuditEventService — so the rule holds no
// matter which controller (or future caller) invokes it.
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuditEventService auditEventService;

    private static final String TARGET_TYPE = "User";
    private static final String DEFAULT_ROLE = "USER";

    @Transactional
    public UserResponse create(UserCreateRequest request, AuthenticatedCaller caller) {
        requireAdmin(caller);

        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new UserAdminException(
                    UserAdminException.Reason.DUPLICATE_EMAIL,
                    "A user with this email already exists");
        }

        Set<Role> roles = resolveRoles(
                request.roles() == null || request.roles().isEmpty()
                        ? Set.of(DEFAULT_ROLE)
                        : request.roles());

        User user = new User(null, email, passwordEncoder.encode(request.password()), UserStatus.ACTIVE);
        roles.forEach(user::addRole);
        userRepository.save(user);

        auditEventService.record(
                AuditEventType.USER_CREATED,
                TARGET_TYPE,
                user.getId(),
                Map.of("email", email, "roles", roleNames(user)));

        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(Pageable pageable, AuthenticatedCaller caller) {
        requireAdmin(caller);

        // Two-step load: page the users, then batch-fetch roles by id.
        // A JOIN FETCH with pagination would force Hibernate to page in
        // memory (HHH90003004); two queries keep pagination in SQL.
        Page<User> page = userRepository.findAll(pageable);
        List<UUID> ids = page.getContent().stream().map(User::getId).toList();
        Map<UUID, User> withRoles = userRepository.findAllWithRolesByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return page.map(u -> UserResponse.from(withRoles.getOrDefault(u.getId(), u)));
    }

    // PATCH semantics: null fields untouched. Both changes refuse to
    // operate on the caller's own account — an admin locking themselves
    // out or dropping their own ADMIN role is a foot-gun with no
    // legitimate use; a second admin can always do it to them.
    @Transactional
    public Optional<UserResponse> update(UUID id, UserUpdateRequest request, AuthenticatedCaller caller) {
        requireAdmin(caller);

        Optional<User> found = userRepository.findByIdWithRoles(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        User user = found.get();

        if (request.status() != null && request.status() != user.getStatus()) {
            requireNotSelf(id, caller, "status");
            UserStatus from = user.getStatus();
            user.setStatus(request.status());

            // Locking/disabling must actually end the user's access:
            // revoke every refresh token so sessions die at the next
            // rotation instead of living out their 7-day expiry.
            if (request.status() != UserStatus.ACTIVE) {
                refreshTokenService.revokeAllForUser(user);
            }

            auditEventService.record(
                    AuditEventType.USER_STATUS_CHANGED,
                    TARGET_TYPE,
                    user.getId(),
                    Map.of("from", from.name(), "to", request.status().name()));
        }

        if (request.roles() != null) {
            requireNotSelf(id, caller, "roles");
            Set<String> before = roleNames(user);
            Set<Role> next = resolveRoles(request.roles());
            user.getRoles().clear();
            next.forEach(user::addRole);

            auditEventService.record(
                    AuditEventType.USER_ROLE_CHANGED,
                    TARGET_TYPE,
                    user.getId(),
                    Map.of("from", before, "to", roleNames(user)));
        }

        userRepository.save(user);
        return Optional.of(UserResponse.from(user));
    }

    private static void requireAdmin(AuthenticatedCaller caller) {
        if (!caller.hasRole(AuthenticatedCaller.ROLE_ADMIN)) {
            throw new AccessDeniedException("User management requires ADMIN role");
        }
    }

    private static void requireNotSelf(UUID targetId, AuthenticatedCaller caller, String what) {
        if (targetId.equals(caller.userId())) {
            throw new UserAdminException(
                    UserAdminException.Reason.SELF_MODIFICATION,
                    "You cannot change your own " + what);
        }
    }

    private Set<Role> resolveRoles(Set<String> names) {
        return names.stream()
                .map(name -> roleRepository.findByName(name)
                        .orElseThrow(() -> new UserAdminException(
                                UserAdminException.Reason.UNKNOWN_ROLE,
                                "Unknown role: " + name)))
                .collect(Collectors.toSet());
    }

    // TreeSet so audit metadata serializes in a stable order.
    private static Set<String> roleNames(User user) {
        return user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
