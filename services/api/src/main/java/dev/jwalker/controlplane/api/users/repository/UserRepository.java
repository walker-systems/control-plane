package dev.jwalker.controlplane.api.users.repository;

import dev.jwalker.controlplane.api.users.model.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.id = :id")
    Optional<User> findByIdWithRoles(@Param("id") UUID id);

    // Batch companion to findByIdWithRoles, for the admin list page:
    // page ids first, then fetch-join roles for just that page.
    // DISTINCT because the join fans out one row per role.
    @Query("SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.roles WHERE u.id IN :ids")
    List<User> findAllWithRolesByIdIn(@Param("ids") Collection<UUID> ids);
}
