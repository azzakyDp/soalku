package id.soalku.auth;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import id.soalku.core.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public TokenResponse issue(long userId, Role role) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).subject(Long.toString(userId))
                .audience(List.of("soalku-api")).issuedAt(now).expiresAt(now.plus(properties.ttl()))
                .id(UUID.randomUUID().toString()).claim("role", role.name()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.ttl().toSeconds());
    }
}
