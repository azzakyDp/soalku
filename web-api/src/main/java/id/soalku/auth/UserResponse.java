package id.soalku.auth;

import id.soalku.core.Role;
import id.soalku.persistence.auth.PenggunaEntity;

public record UserResponse(Long id, String nama, String email, Role role) {
    static UserResponse from(PenggunaEntity user) {
        return new UserResponse(user.getId(), user.getNama(), user.getEmail(), user.getRole());
    }
}
