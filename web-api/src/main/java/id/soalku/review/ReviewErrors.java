package id.soalku.review;

import id.soalku.auth.ApiErrors.ErrorBody;
import org.slf4j.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=QuestionReviewController.class)
class ReviewErrors {
    private static final Logger log=LoggerFactory.getLogger(ReviewErrors.class);
    @ExceptionHandler(ReviewFailure.class)
    ResponseEntity<ErrorBody> rejected(ReviewFailure failure) {
        log.atWarn().addKeyValue("event","review.rejected").addKeyValue("code",failure.code)
                .addKeyValue("status",failure.status).log("Question review rejected");
        return ResponseEntity.status(failure.status).body(new ErrorBody(failure.code,failure.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> invalidParameter(MethodArgumentTypeMismatchException failure) {
        log.atWarn().addKeyValue("event","review.invalid_parameter").log("Invalid review parameter");
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST","Parameter review tidak valid."));
    }
    @ExceptionHandler({PessimisticLockingFailureException.class,QueryTimeoutException.class,
            org.springframework.transaction.TransactionTimedOutException.class})
    ResponseEntity<ErrorBody> busy(RuntimeException failure) {
        log.atWarn().addKeyValue("event","review.database_busy").addKeyValue("cause",failure.getClass().getSimpleName())
                .log("Review database wait exceeded its bound");
        return ResponseEntity.status(503).body(new ErrorBody("REVIEW_BUSY","Soal sedang diproses. Silakan coba kembali."));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException failure) {
        // PostgreSQL exception text can include complete question/answer rows; preserve a safe stack only.
        var safe=new IllegalStateException("Review persistence failure; database message redacted");safe.setStackTrace(failure.getStackTrace());
        String sqlState="unknown";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof java.sql.SQLException sql) sqlState=sql.getSQLState();
        log.atError().addKeyValue("event","review.persistence_failed").addKeyValue("cause",failure.getClass().getSimpleName())
                .addKeyValue("sql_state",sqlState).setCause(safe).log("Question review transaction failed");
        return ResponseEntity.internalServerError().body(new ErrorBody("REVIEW_FAILED","Review belum dapat disimpan. Silakan coba kembali."));
    }
}
