package dev.jwalker.controlplane.api.auth.repository;

import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.users.model.User;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUser(User user);

    void deleteByUser(User user);

    @Query("""
    select count(rt)
    from RefreshToken rt
    where rt.user = :user
      and rt.revokedAt is null
      and rt.expiresAt > :now
    """)
    long countActiveTokensForUser(User user, OffsetDateTime now);
}
