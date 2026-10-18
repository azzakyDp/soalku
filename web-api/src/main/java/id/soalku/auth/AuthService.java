package id.soalku.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import id.soalku.core.Role;
import id.soalku.persistence.auth.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final PenggunaRepository users;
    private final KodeUndanganRepository invitations;
    private final PasswordEncoder passwords;
    private final JwtService tokens;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(PenggunaRepository users, KodeUndanganRepository invitations,
                       PasswordEncoder passwords, JwtService tokens, Clock clock) {
        this.users = users;
        this.invitations = invitations;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(timeout = 15)
    public UserResponse register(RegisterRequest request) {
        checkPasswordLength(request.password());
        String email = normalizeEmail(request.email());
        if (users.findByNormalizedEmail(email).isPresent()) {
            throw new AuthFailure(HttpStatus.CONFLICT, "EMAIL_REGISTERED", "Email sudah terdaftar.");
        }
        if (request.role() == Role.SISWA && request.kodeUndangan() != null && !request.kodeUndangan().isBlank()) {
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "UNEXPECTED_INVITATION", "Pendaftaran Siswa tidak memakai kode undangan.");
        }

        // Hash before taking the invitation lock, keeping the critical section short.
        String hash = passwords.encode(request.password());
        KodeUndanganEntity invitation = null;
        if (request.role() == Role.MENTOR) {
            if (request.kodeUndangan() == null || request.kodeUndangan().isBlank()) throw invalidInvitation();
            invitation = invitations.findForRegistration(request.kodeUndangan().strip()).orElseThrow(AuthService::invalidInvitation);
            boolean mentorIssuer = users.findById(invitation.getDibuatOleh())
                    .map(user -> user.getRole() == Role.MENTOR).orElse(false);
            if (!mentorIssuer || !invitation.isUsableAt(clock.instant())) throw invalidInvitation();
        }

        var user = users.saveAndFlush(new PenggunaEntity(request.nama().strip(), email, hash, request.role()));
        if (invitation != null) invitation.markUsed();
        log.atInfo().addKeyValue("event", "auth.register").addKeyValue("user_id", user.getId())
                .addKeyValue("role", user.getRole()).log("Registration succeeded");
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        checkPasswordLength(request.password());
        var user = users.findByNormalizedEmail(normalizeEmail(request.email()));
        boolean matches = passwords.matches(request.password(), user.map(PenggunaEntity::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            throw new AuthFailure(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email atau password salah.");
        }
        var account = user.orElseThrow();
        var response = tokens.issue(account.getId(), account.getRole());
        log.atInfo().addKeyValue("event", "auth.login").addKeyValue("user_id", account.getId()).log("Login succeeded");
        return response;
    }

    @Transactional(readOnly = true)
    public UserResponse me(long userId) {
        var user = users.findById(userId).orElseThrow(() ->
                new AuthFailure(HttpStatus.UNAUTHORIZED, "INVALID_ACCOUNT", "Akun tidak tersedia."));
        log.atDebug().addKeyValue("event", "auth.me").addKeyValue("user_id", userId).log("Account identity read");
        return UserResponse.from(user);
    }

    private static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    private static void checkPasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD_LENGTH", "Password maksimal 72 byte UTF-8.");
    }

    private static AuthFailure invalidInvitation() {
        return new AuthFailure(HttpStatus.BAD_REQUEST, "INVALID_INVITATION", "Kode undangan tidak valid atau sudah kedaluwarsa.");
    }
}
