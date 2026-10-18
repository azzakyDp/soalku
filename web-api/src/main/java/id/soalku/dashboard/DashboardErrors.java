package id.soalku.dashboard;

import id.soalku.auth.ApiErrors.ErrorBody;
import org.slf4j.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes=DashboardController.class)
class DashboardErrors {
    private static final Logger log=LoggerFactory.getLogger(DashboardErrors.class);
    @ExceptionHandler(DashboardFailure.class)
    ResponseEntity<ErrorBody> rejected(DashboardFailure failure) {
        log.atWarn().addKeyValue("event","dashboard.rejected").addKeyValue("code",failure.code)
                .addKeyValue("status",failure.status).log("Dashboard access rejected");
        return ResponseEntity.status(failure.status).body(new ErrorBody(failure.code,failure.getMessage()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> invalid(MethodArgumentTypeMismatchException failure) {
        log.atWarn().addKeyValue("event","dashboard.invalid_parameter").log("Invalid dashboard parameter");
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST","Gunakan parameter halaman dan ukuran berupa bilangan bulat."));
    }
    @ExceptionHandler({PessimisticLockingFailureException.class,QueryTimeoutException.class,org.springframework.transaction.TransactionTimedOutException.class})
    ResponseEntity<ErrorBody> busy(RuntimeException failure) {
        log.atWarn().addKeyValue("event","dashboard.database_busy").addKeyValue("cause",failure.getClass().getSimpleName()).log("Dashboard database wait exceeded its bound");
        return ResponseEntity.status(503).body(new ErrorBody("DASHBOARD_BUSY","Database sedang sibuk. Silakan muat ulang dashboard."));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException failure) {
        var safe=new IllegalStateException("Dashboard query failed; database message redacted");safe.setStackTrace(failure.getStackTrace());
        String state="unknown";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof java.sql.SQLException sql) state=sql.getSQLState();
        log.atError().addKeyValue("event","dashboard.query_failed").addKeyValue("cause",failure.getClass().getSimpleName())
                .addKeyValue("sql_state",state).setCause(safe).log("Dashboard query failed");
        return ResponseEntity.internalServerError().body(new ErrorBody("DASHBOARD_FAILED","Dashboard belum dapat dimuat."));
    }
}
