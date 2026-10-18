package id.soalku.ai;

import id.soalku.auth.ApiErrors.ErrorBody;
import org.slf4j.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=MaterialGenerationController.class)
class GenerationErrors {
    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class,
            org.springframework.dao.QueryTimeoutException.class,org.springframework.transaction.TransactionTimedOutException.class})
    ResponseEntity<ErrorBody> busy(RuntimeException failure) {
        LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("event","ai.database_busy")
                .addKeyValue("cause",failure.getClass().getSimpleName()).log("Generation database wait exceeded its bound");
        return ResponseEntity.status(503).body(new ErrorBody("GENERATION_BUSY","Database sedang sibuk. Materi tetap tersimpan; silakan retry."));
    }
    @ExceptionHandler(GenerationFailure.class)
    ResponseEntity<ErrorBody> failure(GenerationFailure failure) {
        LoggerFactory.getLogger(getClass()).atWarn().addKeyValue("event","ai.access_rejected").addKeyValue("code",failure.code).log("Generation request rejected");
        return ResponseEntity.status(failure.status).body(new ErrorBody(failure.code,failure.getMessage()));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ErrorBody> database(org.springframework.dao.DataAccessException failure) {
        var diagnostic=new IllegalStateException("Generation persistence failure; database message redacted to protect material content");
        diagnostic.setStackTrace(failure.getStackTrace());
        String sqlState="unknown";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof java.sql.SQLException sql) sqlState=sql.getSQLState();
        LoggerFactory.getLogger(getClass()).atError().addKeyValue("event","ai.persistence_failed")
                .addKeyValue("cause",failure.getClass().getSimpleName()).addKeyValue("sql_state",sqlState).setCause(diagnostic)
                .log("Generated section transaction failed; retry is safe");
        return ResponseEntity.internalServerError().body(new ErrorBody("GENERATION_PERSISTENCE_FAILED","Hasil generate belum dapat disimpan. Materi tetap tersedia; silakan retry."));
    }
}
