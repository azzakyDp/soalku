package id.soalku.material;

import id.soalku.auth.ApiErrors.ErrorBody;
import id.soalku.extraction.ExtractionFailure;
import org.slf4j.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class MaterialErrors {
    private static final Logger log=LoggerFactory.getLogger(MaterialErrors.class);
    @ExceptionHandler(ExtractionFailure.class)
    ResponseEntity<ErrorBody> extraction(ExtractionFailure failure) {
        int status=switch(failure.code()) {
            case "UNSUPPORTED_FORMAT" -> 415;
            case "FILE_TOO_LARGE" -> 413;
            case "EMPTY_FILE" -> 400;
            default -> 422;
        };
        log.atWarn().addKeyValue("event","material.extraction_rejected").addKeyValue("code",failure.code())
                .addKeyValue("cause",failure.getCause()==null ? "validation" : failure.getCause().getClass().getSimpleName())
                .log("Document extraction rejected");
        return ResponseEntity.status(status).body(new ErrorBody(failure.code(),failure.getMessage()));
    }
    @ExceptionHandler(MaterialFailure.class)
    ResponseEntity<ErrorBody> material(MaterialFailure failure) {
        if (failure.status>=500) log.atError().addKeyValue("event","material.upload_failed").setCause(failure).log("Material upload failed");
        else log.atWarn().addKeyValue("event","material.upload_rejected").addKeyValue("code",failure.code).log("Material upload rejected");
        return ResponseEntity.status(failure.status).body(new ErrorBody(failure.code,failure.getMessage()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> oversized(MaxUploadSizeExceededException failure) {
        return material(new MaterialFailure(413,"FILE_TOO_LARGE","Ukuran file maksimal 10 MB."));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,MultipartException.class})
    ResponseEntity<ErrorBody> metadata(Exception failure) {
        return material(new MaterialFailure(400,"INVALID_UPLOAD","Kirim multipart file, judul, dan jenjang SD atau SMP yang valid."));
    }
}
