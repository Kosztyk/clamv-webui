package info.trizub.clamav.webclient.api;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Legacy /api/** JSON error contract. Never reflect the exception message, scan target, endpoint
 * hostname, path or internal implementation details. The v1 API keeps its existing error handler
 * and timestamped contract.
 */
@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler({
    IllegalArgumentException.class,
    BindException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class,
    HttpMessageNotReadableException.class,
    ConstraintViolationException.class
  })
  public ResponseEntity<Map<String, String>> invalid(Exception ex) {
    return error(
        HttpStatus.BAD_REQUEST, "invalid_request", "Invalid scan request or path policy violation");
  }

  @ExceptionHandler({NoSuchElementException.class})
  public ResponseEntity<Map<String, String>> missing(NoSuchElementException ex) {
    return error(HttpStatus.NOT_FOUND, "not_found", "Requested resource not found");
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> status(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
    return error(
        status,
        switch (status) {
          case NOT_FOUND -> "not_found";
          case BAD_REQUEST -> "invalid_request";
          case FORBIDDEN -> "forbidden";
          case CONFLICT -> "conflict";
          case SERVICE_UNAVAILABLE -> "unavailable";
          default -> "request_failed";
        },
        status.getReasonPhrase());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> oversized(MaxUploadSizeExceededException ex) {
    return error(HttpStatus.PAYLOAD_TOO_LARGE, "upload_too_large", "Upload too large");
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException ex) {
    return error(HttpStatus.FORBIDDEN, "forbidden", "Access denied");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> internal(Exception ex) {
    // Keep diagnostics on the server only, without logging input values.
    log.error("Legacy API request failed ({})", ex.getClass().getSimpleName());
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal Server Error");
  }

  private static ResponseEntity<Map<String, String>> error(
      HttpStatus status, String code, String message) {
    // Stable legacy JSON for all invalid paths; callers can compare responses
    // without timestamps or accidentally exposed path-specific exception text.
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(Map.of("error", code, "message", message));
  }
}
