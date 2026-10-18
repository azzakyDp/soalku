package id.soalku.practice;

import id.soalku.auth.ApiErrors.ErrorBody;
import org.slf4j.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes=PracticeController.class)
class PracticeErrors {
    private static final Logger log=LoggerFactory.getLogger(PracticeErrors.class);
    @ExceptionHandler(PracticeFailure.class)
    ResponseEntity<ErrorBody> rejected(PracticeFailure failure) {
        var event=failure.status>=500?log.atError():log.atWarn();
        if(failure.getCause()!=null) {
            var safe=new IllegalStateException("Practice snapshot processing failed; content redacted");
            safe.setStackTrace(failure.getCause().getStackTrace());
            event.addKeyValue("cause",failure.getCause().getClass().getSimpleName()).setCause(safe);
        }
        event.addKeyValue("event","practice.rejected").addKeyValue("code",failure.code)
                .addKeyValue("status",failure.status).log("Practice request rejected");
        return ResponseEntity.status(failure.status).body(new ErrorBody(failure.code,failure.getMessage()));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<ErrorBody> invalid(Exception failure) {
        log.atWarn().addKeyValue("event","practice.invalid_parameter").log("Invalid practice parameter");
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST","Gunakan Idempotency-Key UUID dan input latihan yang valid."));
    }
    @ExceptionHandler({PessimisticLockingFailureException.class,QueryTimeoutException.class,org.springframework.transaction.TransactionTimedOutException.class})
    ResponseEntity<ErrorBody> busy(RuntimeException failure) {
        log.atWarn().addKeyValue("event","practice.database_busy").addKeyValue("cause",failure.getClass().getSimpleName()).log("Practice database wait exceeded its bound");
        return ResponseEntity.status(503).body(new ErrorBody("PRACTICE_BUSY","Database sedang sibuk. Ulangi dengan Idempotency-Key yang sama."));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException failure) {
        var safe=new IllegalStateException("Practice persistence failure; database message redacted");safe.setStackTrace(failure.getStackTrace());
        String state="unknown";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof java.sql.SQLException sql) state=sql.getSQLState();
        log.atError().addKeyValue("event","practice.persistence_failed").addKeyValue("cause",failure.getClass().getSimpleName())
                .addKeyValue("sql_state",state).setCause(safe).log("Practice session transaction failed");
        return ResponseEntity.internalServerError().body(new ErrorBody("PRACTICE_FAILED","Sesi latihan belum dapat disimpan. Silakan coba kembali."));
    }
}
