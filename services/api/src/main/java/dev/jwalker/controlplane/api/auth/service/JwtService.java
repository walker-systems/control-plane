package dev.jwalker.controlplane.api.auth.service;

import dev.jwalker.controlplane.api.auth.config.SecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String ISSUER = "https://control-plane.local";

    private final JwtEncoder encoder;
    private final Duration accessTokenTtl;

    public JwtService(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.accessTokenTtl = Duration.ofMinutes(props.accessTokenMinutes());
    }

    public IssuedAccessToken issueAccessToken(AppUserDetails user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenTtl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("email", user.getUsername())
                .claim("roles", List.copyOf(user.getRoleNames()))
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedAccessToken(token, expiresAt, accessTokenTtl);
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    public record IssuedAccessToken(String token, Instant expiresAt, Duration ttl) {
    }
}
