package dev.jwalker.controlplane.api.auth.service;

import dev.jwalker.controlplane.api.auth.config.SecurityProperties;
import dev.jwalker.controlplane.api.auth.model.RefreshToken;
import dev.jwalker.controlplane.api.auth.repository.RefreshTokenRepository;
import dev.jwalker.controlplane.api.users.model.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final int RAW_TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final SecurityProperties props;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, SecurityProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Transactional
    public IssuedRefreshToken issue(User user) {
        String rawToken = generateRawToken();
        String tokenHash = hash(rawToken);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(props.refreshTokenDays());

        RefreshToken token = new RefreshToken(null, user, tokenHash, expiresAt);
        repository.save(token);

        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findActive(String rawToken) {
        String tokenHash = hash(rawToken);
        return repository.findByTokenHash(tokenHash)
                .filter(t -> !t.isRevoked() && !t.isExpired());
    }

    @Transactional
    public void revoke(RefreshToken token) {
        token.revoke();
        repository.save(token);
    }

    @Transactional
    public void revokeIfPresent(String rawToken) {
        String tokenHash = hash(rawToken);
        repository.findByTokenHash(tokenHash)
                .filter(t -> !t.isRevoked())
                .ifPresent(t -> {
                    t.revoke();
                    repository.save(t);
                });
    }

    private String generateRawToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record IssuedRefreshToken(String rawToken, OffsetDateTime expiresAt) {
    }
}
