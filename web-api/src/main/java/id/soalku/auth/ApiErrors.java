package id.soalku.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    public record ErrorBody(String code, String message) { }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ErrorBody> auth(AuthFailure failure) {
        log.atWarn().addKeyValue("event", "auth.rejected").addKeyValue("code", failure.code())
                .addKeyValue("status", failure.status().value()).log("Authentication request rejected");
        return ResponseEntity.status(failure.status()).body(new ErrorBody(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorBody> invalidRequest(Exception exception) {
        // Do not log validation exceptions: their details can contain submitted secrets.
        log.atWarn().addKeyValue("event", "auth.invalid_request").log("Invalid request body");
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "Periksa format dan kelengkapan input."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> conflict(DataIntegrityViolationException exception) {
        // Handle the known email race; unexpected integrity errors must not be hidden.
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "uq_pengguna_email".equals(violation.getConstraintName())) {
                log.atWarn().addKeyValue("event", "auth.email_conflict").log("Concurrent duplicate registration rejected");
                return ResponseEntity.status(409).body(new ErrorBody("EMAIL_REGISTERED", "Email sudah terdaftar."));
            }
            cause = cause.getCause();
        }
        log.error("Unexpected database integrity failure", exception);
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR", "Permintaan tidak dapat diproses."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> forbidden(AccessDeniedException exception) {
        log.atWarn().addKeyValue("event", "security.forbidden").log("Insufficient role");
        return ResponseEntity.status(403).body(new ErrorBody("FORBIDDEN", "Akses tidak diizinkan."));
    }
}
