package id.soalku.auth;

import id.soalku.core.Role;
import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Size(max = 150) String nama,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull Role role,
        @Size(max = 100) String kodeUndangan) {
    @Override
    public String toString() { return "RegisterRequest[credentials=REDACTED]"; }
}
