package id.soalku.auth;

import org.springframework.http.HttpStatus;

final class AuthFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    AuthFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    HttpStatus status() { return status; }
    String code() { return code; }
}
