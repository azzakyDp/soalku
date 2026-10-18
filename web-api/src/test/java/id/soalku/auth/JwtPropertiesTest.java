package id.soalku.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtPropertiesTest {
    @Test
    void missingMalformedOrShortKeysAreRejectedWithoutExposingTheirValue() {
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(null, "soalku", Duration.ofMinutes(15)));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties("not-base64!", "soalku", Duration.ofMinutes(15)));
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new JwtProperties(shortKey, "soalku", Duration.ofMinutes(15)));
        assertFalse(failure.getMessage().contains(shortKey));
    }

    @Test
    void unboundedOrInvalidTokenLifetimesAreRejected() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String key = Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(key, "soalku", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(key, "soalku", Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(key, "", Duration.ofMinutes(15)));
        var valid = new JwtProperties(key, "soalku", Duration.ofMinutes(15));
        assertFalse(valid.toString().contains(key));
    }
}
