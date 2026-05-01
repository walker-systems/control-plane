package dev.jwalker.controlplane.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.jwalker.controlplane.api.auth.config.SecurityProperties;
import dev.jwalker.controlplane.api.users.model.Role;
import dev.jwalker.controlplane.api.users.model.User;
import dev.jwalker.controlplane.api.users.model.UserStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtServiceTest {

    private static final String SECRET = "test-secret-must-be-at-least-32-bytes-long-xx";

    private final SecurityProperties props = new SecurityProperties(SECRET, 15, 7);
    private final NimbusJwtEncoder encoder = buildEncoder(SECRET);
    private final JwtDecoder decoder = buildDecoder(SECRET);
    private final JwtService jwtService = new JwtService(encoder, props);

    @Test
    void issueAccessToken_includesSubjectEmailAndRoles() {
        AppUserDetails principal = principal("admin@example.com", "ADMIN", "USER");

        JwtService.IssuedAccessToken issued = jwtService.issueAccessToken(principal);
        Jwt decoded = decoder.decode(issued.token());

        assertThat(decoded.getSubject()).isEqualTo(principal.getId().toString());
        assertThat(decoded.<String>getClaim("email")).isEqualTo("admin@example.com");
        assertThat(decoded.<java.util.List<String>>getClaim("roles"))
                .containsExactlyInAnyOrder("ADMIN", "USER");
        assertThat(decoded.getIssuer()).hasToString("https://control-plane.local");
    }

    @Test
    void issueAccessToken_setsExpirationFromConfig() {
        AppUserDetails principal = principal("u@example.com", "USER");

        Instant before = Instant.now();
        JwtService.IssuedAccessToken issued = jwtService.issueAccessToken(principal);
        Instant after = Instant.now();

        Jwt decoded = decoder.decode(issued.token());
        assertThat(decoded.getExpiresAt())
                .isAfterOrEqualTo(before.plusSeconds(15 * 60 - 1))
                .isBeforeOrEqualTo(after.plusSeconds(15 * 60 + 1));
    }

    private static AppUserDetails principal(String email, String... roleNames) {
        User user = new User(UUID.randomUUID(), email, "hash", UserStatus.ACTIVE);
        for (String roleName : roleNames) {
            user.addRole(new Role(UUID.randomUUID(), roleName));
        }
        return new AppUserDetails(user);
    }

    private static NimbusJwtEncoder buildEncoder(String secret) {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(secret.getBytes(StandardCharsets.UTF_8))
                .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwks);
    }

    private static JwtDecoder buildDecoder(String secret) {
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
