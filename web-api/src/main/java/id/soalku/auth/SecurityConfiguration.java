package id.soalku.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecretKey jwtKey(JwtProperties properties) {
        return new SecretKeySpec(Base64.getDecoder().decode(properties.secret()), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, JwtProperties properties, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean valid = jwt.getSubject() != null && jwt.getSubject().matches("[1-9][0-9]{0,18}");
            if (valid) {
                try { Long.parseLong(jwt.getSubject()); }
                catch (NumberFormatException exception) { valid = false; }
            }
            String role = jwt.getClaimAsString("role");
            valid = valid && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && !jwt.getIssuedAt().isAfter(clock.instant())
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && jwt.getAudience() != null && jwt.getAudience().contains("soalku-api")
                    && ("SISWA".equals(role) || "MENTOR".equals(role));
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims", null));
        };
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer()), requiredClaims));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(jwt ->
                List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        // Authentication: signed bearer tokens only; no cookies or server sessions.
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable);
        // AuthorizationFilter runs after JWT authentication and before the MVC controller.
        // Reserve the Mentor namespace for every HTTP method, including its root path.
        http.authorizeHttpRequests(access -> access
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/materi/upload").hasRole("SISWA")
                .requestMatchers(HttpMethod.POST, "/api/materi/*/generate").hasRole("SISWA")
                .requestMatchers("/api/latihan", "/api/latihan/**").hasRole("SISWA")
                .requestMatchers("/api/mentor", "/api/mentor/**").hasRole("MENTOR")
                .anyRequest().authenticated());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeSecurityError(mapper, response, 401))
                .accessDeniedHandler((request, response, exception) -> writeSecurityError(mapper, response, 403)));
        http.oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                .authenticationEntryPoint((request, response, exception) -> writeSecurityError(mapper, response, 401))
                .accessDeniedHandler((request, response, exception) -> writeSecurityError(mapper, response, 403)));
        return http.build();
    }

    private static void writeSecurityError(ObjectMapper mapper, HttpServletResponse response, int status)
            throws java.io.IOException {
        log.atWarn().addKeyValue("event", "security.rejected").addKeyValue("status", status).log("Access rejected");
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), new ApiErrors.ErrorBody(
                status == 401 ? "UNAUTHORIZED" : "FORBIDDEN",
                status == 401 ? "Token tidak ada atau tidak valid." : "Akses tidak diizinkan."));
    }
}
