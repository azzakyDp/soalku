package id.soalku.auth;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("soalku.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {
    public JwtProperties {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("JWT secret is required");
        try {
            if (Base64.getDecoder().decode(secret).length < 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT secret must be Base64 encoding at least 32 random bytes");
        }
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("JWT issuer is required");
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("JWT TTL must be between one second and one hour");
    }
    @Override
    public String toString() { return "JwtProperties[secret=REDACTED]"; }
}
