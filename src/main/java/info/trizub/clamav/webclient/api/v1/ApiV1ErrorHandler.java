package info.trizub.clamav.webclient.api.v1;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(basePackages = "info.trizub.clamav.webclient.api.v1")
public class ApiV1ErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, BindException.class, MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception e) { return error(HttpStatus.BAD_REQUEST, "invalid_request"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> oversized(Exception e) { return error(HttpStatus.PAYLOAD_TOO_LARGE, "upload_too_large"); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(Exception e) { return error(HttpStatus.FORBIDDEN, "forbidden"); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return error(HttpStatus.valueOf(e.getStatusCode().value()), e.getStatusCode().value() == 404 ? "not_found" : "request_failed");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error"); }
    private ResponseEntity<Map<String,Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
            .body(Map.of("error", code, "message", status.getReasonPhrase(), "timestamp", Instant.now().toString()));
    }
}
